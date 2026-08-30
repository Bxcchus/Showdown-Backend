#![cfg_attr(windows, windows_subsystem = "windows")]

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, HeaderValue, Method, StatusCode},
    routing::{get, post},
};
use base64::{Engine, engine::general_purpose::STANDARD};
use reqwest::{Client, IntoUrl, RequestBuilder, Url, redirect::Policy};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    env, fs,
    net::{IpAddr, SocketAddr},
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::{Mutex, RwLock};
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing::{error, info};

#[cfg(windows)]
mod windows_ui;

#[derive(Clone)]
struct AppState {
    job: Arc<RwLock<JobStatus>>,
    backend_http: BackendHttp,
    backend_base_url: Arc<String>,
    watcher_client_id: Arc<String>,
    watcher_client_secret: Arc<String>,
    service_tokens: Arc<Mutex<HashMap<String, CachedServiceToken>>>,
    session_token: Arc<String>,
    allowed_origins: Arc<Vec<String>>,
}

#[derive(Clone)]
struct BackendHttp(Client);

impl BackendHttp {
    fn new() -> Result<Self, reqwest::Error> {
        Client::builder()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(20))
            .build()
            .map(Self)
    }

    fn get<U: IntoUrl>(&self, url: U) -> RequestBuilder {
        self.0.get(url)
    }

    fn post<U: IntoUrl>(&self, url: U) -> RequestBuilder {
        self.0.post(url)
    }

    fn put<U: IntoUrl>(&self, url: U) -> RequestBuilder {
        self.0.put(url)
    }
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct JobStatus {
    match_id: Option<String>,
    state: String,
    detail: Option<String>,
    outcome: Option<String>,
    objective: Option<String>,
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
    component: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WatcherSession {
    token: String,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct RiotIdentity {
    puuid: String,
    game_name: String,
    tag_line: String,
    riot_id: String,
    profile_icon_id: i64,
    summoner_level: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct StartRequest {
    match_id: String,
    watcher_token: String,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct BotDuelStartRequest {
    match_id: String,
}

#[derive(Deserialize)]
struct OAuthTokenResponse {
    access_token: String,
    expires_in: u64,
}

#[derive(Clone)]
struct CachedServiceToken {
    access_token: String,
    refresh_at: Instant,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct BotMatchAssignment {
    match_id: String,
    lobby_name: String,
    lobby_password: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BotMatchResult<'a> {
    human_won: bool,
    objective: &'a str,
    observed_at: String,
}

struct BotOutcome {
    objective: &'static str,
    human_won: bool,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Assignment {
    match_id: String,
    role: String,
    own_riot_id: String,
    opponent_riot_id: String,
    lobby_name: String,
    lobby_password: String,
}

#[derive(Clone)]
struct Lcu {
    base: String,
    password: String,
    http: Client,
}

struct LiveClient {
    http: Client,
}

#[derive(Clone, Copy)]
enum LiveEndpoint {
    EventData,
    PlayerList,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "showdown_watcher=info".into()),
        )
        .init();
    let backend_http = BackendHttp::new().expect("client HTTP backend strict");
    let backend_base_url = normalize_backend_base(
        &env::var("SHOWDOWN_SERVER_BASE_URL").unwrap_or_else(|_| "http://localhost:8088".into()),
    )
    .expect("SHOWDOWN_SERVER_BASE_URL invalide");
    let production = production_mode();
    let allowed_origins = configured_web_origins().expect("origines web invalides");
    let watcher_client_id =
        env::var("SHOWDOWN_WATCHER_CLIENT_ID").unwrap_or_else(|_| "pinkward-watcher".into());
    let watcher_client_secret = env::var("SHOWDOWN_WATCHER_CLIENT_SECRET").unwrap_or_default();
    validate_watcher_credential(production, &watcher_client_id, &watcher_client_secret)
        .expect("credential Watcher invalide");
    let web_origins = allowed_origins
        .iter()
        .filter_map(|origin| origin.parse::<HeaderValue>().ok())
        .collect::<Vec<_>>();
    let state = AppState {
        job: Arc::new(RwLock::new(JobStatus {
            state: "IDLE".into(),
            ..Default::default()
        })),
        backend_http,
        backend_base_url: Arc::new(backend_base_url),
        watcher_client_id: Arc::new(watcher_client_id),
        watcher_client_secret: Arc::new(watcher_client_secret),
        service_tokens: Arc::new(Mutex::new(HashMap::new())),
        session_token: Arc::new(format!(
            "{}{}",
            uuid::Uuid::new_v4().simple(),
            uuid::Uuid::new_v4().simple()
        )),
        allowed_origins: Arc::new(allowed_origins),
    };
    let cors = CorsLayer::new()
        .allow_origin(web_origins)
        .allow_methods([Method::GET, Method::POST])
        .allow_headers(tower_http::cors::Any);
    let mut app = Router::new()
        .route(
            "/health",
            get(|| async {
                Json(Health {
                    status: "UP",
                    component: "showdown-watcher",
                })
            }),
        )
        .route("/v1/identity", get(identity))
        .route("/v1/session", post(create_session))
        .route("/v1/duels/start", post(start))
        .route("/v1/duels/status", get(status));
    if !production {
        app = app.route("/v1/bot-duels/start", post(start_bot_duel));
    }
    let app = app
        .layer(cors)
        .layer(TraceLayer::new_for_http())
        .with_state(state.clone());
    let address = configured_watcher_address().expect("SHOWDOWN_WATCHER_ADDRESS invalide");
    let listener = match tokio::net::TcpListener::bind(address).await {
        Ok(listener) => listener,
        Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => {
            error!(%address, "un watcher est déjà actif sur ce port");
            return;
        }
        Err(error) => {
            error!(%address, %error, "impossible de démarrer le watcher");
            return;
        }
    };
    let shutdown_requested = Arc::new(AtomicBool::new(false));
    #[cfg(windows)]
    let ui_thread = windows_ui::spawn(state, shutdown_requested.clone());
    info!(%address, "watcher ready");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown(shutdown_requested.clone()))
        .await
        .expect("watcher server");
    shutdown_requested.store(true, Ordering::Release);
    #[cfg(windows)]
    let _ = ui_thread.join();
}

async fn shutdown(requested: Arc<AtomicBool>) {
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {},
        _ = async {
            while !requested.load(Ordering::Acquire) {
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        } => {},
    }
    requested.store(true, Ordering::Release);
}

async fn identity(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<RiotIdentity>, ApiError> {
    require_local_session(&headers, &state)?;
    Ok(Json(read_riot_identity().await?))
}

async fn read_riot_identity() -> Result<RiotIdentity, ApiError> {
    if simulated()
        && let Ok(value) = env::var("SHOWDOWN_RIOT_ID")
    {
        let puuid =
            env::var("SHOWDOWN_RIOT_PUUID").unwrap_or_else(|_| "showdown-simulated-puuid".into());
        return split_riot_id(&value, &puuid);
    }
    let lcu = Lcu::discover()?;
    let value = lcu.get("/lol-summoner/v1/current-summoner").await?;
    let puuid = value
        .get("puuid")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim();
    let game = value
        .get("gameName")
        .and_then(Value::as_str)
        .or_else(|| value.get("displayName").and_then(Value::as_str))
        .unwrap_or_default();
    let tag = value
        .get("tagLine")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if puuid.len() < 16 || game.is_empty() || tag.is_empty() {
        return Err(ApiError::bad_gateway(
            "Le client League ne fournit pas encore une identité Riot vérifiable",
        ));
    }
    let profile_icon_id = value
        .get("profileIconId")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let summoner_level = value
        .get("summonerLevel")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    if profile_icon_id <= 0 || summoner_level <= 0 {
        return Err(ApiError::bad_gateway("Le profil Riot local est incomplet"));
    }
    Ok(RiotIdentity {
        puuid: puuid.into(),
        game_name: game.into(),
        tag_line: tag.into(),
        riot_id: format!("{game}#{tag}"),
        profile_icon_id,
        summoner_level,
    })
}

async fn service_access_token(state: &AppState, scope: &str) -> Result<String, ApiError> {
    if let Some(cached) = state.service_tokens.lock().await.get(scope).cloned()
        && Instant::now() < cached.refresh_at
    {
        return Ok(cached.access_token);
    }
    request_service_access_token(state, scope).await
}

async fn request_service_access_token(state: &AppState, scope: &str) -> Result<String, ApiError> {
    if state.watcher_client_secret.trim().is_empty() {
        return Err(ApiError::service_unavailable(
            "Le Watcher n'a pas reçu ses identifiants techniques",
        ));
    }
    let response = state
        .backend_http
        .post(format!("{}/oauth2/token", state.backend_base_url))
        .basic_auth(
            state.watcher_client_id.as_str(),
            Some(state.watcher_client_secret.as_str()),
        )
        .form(&[("grant_type", "client_credentials"), ("scope", scope)])
        .send()
        .await
        .map_err(|_| ApiError::bad_gateway("Le serveur d'identité est inaccessible"))?;
    if !response.status().is_success() {
        return Err(ApiError::bad_gateway(&format!(
            "Authentification technique du Watcher refusée ({})",
            response.status()
        )));
    }
    let token = response
        .json::<OAuthTokenResponse>()
        .await
        .map_err(|_| ApiError::bad_gateway("Réponse OAuth2 invalide"))?;
    if token.access_token.trim().is_empty() || token.expires_in == 0 {
        return Err(ApiError::bad_gateway("Jeton OAuth2 vide"));
    }
    let refresh_after = token.expires_in.saturating_sub(20).max(1);
    state.service_tokens.lock().await.insert(
        scope.to_owned(),
        CachedServiceToken {
            access_token: token.access_token.clone(),
            refresh_at: Instant::now() + Duration::from_secs(refresh_after),
        },
    );
    Ok(token.access_token)
}

async fn invalidate_service_access_token(state: &AppState, scope: &str) {
    state.service_tokens.lock().await.remove(scope);
}

async fn send_service_request<F>(
    state: &AppState,
    scope: &str,
    build: F,
) -> Result<reqwest::Response, ApiError>
where
    F: Fn(&str) -> RequestBuilder,
{
    for attempt in 0..2 {
        let access_token = service_access_token(state, scope).await?;
        let response = build(&access_token)
            .send()
            .await
            .map_err(|_| ApiError::bad_gateway("Le backend Showdown est inaccessible"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED && attempt == 0 {
            invalidate_service_access_token(state, scope).await;
            continue;
        }
        return Ok(response);
    }
    Err(ApiError::bad_gateway(
        "Authentification technique du Watcher refusée",
    ))
}

async fn create_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<WatcherSession>, ApiError> {
    let origin = headers
        .get("origin")
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if !state
        .allowed_origins
        .iter()
        .any(|allowed| allowed == origin)
    {
        return Err(ApiError::forbidden("Origine web non autorisée"));
    }
    Ok(Json(WatcherSession {
        token: state.session_token.as_str().to_owned(),
    }))
}

fn require_local_session(headers: &HeaderMap, state: &AppState) -> Result<(), ApiError> {
    let supplied = headers
        .get("x-showdown-watcher-token")
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if supplied.len() != state.session_token.len() || supplied != state.session_token.as_str() {
        return Err(ApiError::forbidden("Session locale du Watcher invalide"));
    }
    Ok(())
}

async fn start(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(mut request): Json<StartRequest>,
) -> Result<(StatusCode, Json<JobStatus>), ApiError> {
    require_local_session(&headers, &state)?;
    if request.match_id.trim().is_empty() || request.watcher_token.trim().is_empty() {
        return Err(ApiError::bad_request("matchId et watcherToken sont requis"));
    }
    request.match_id = canonical_uuid(&request.match_id, "matchId")?;
    {
        let mut job = state.job.write().await;
        if !matches!(job.state.as_str(), "IDLE" | "COMPLETED" | "ERROR") {
            return Err(ApiError::conflict("Un duel est déjà suivi"));
        }
        *job = JobStatus {
            match_id: Some(request.match_id.clone()),
            state: "STARTING".into(),
            detail: None,
            ..Default::default()
        };
    }
    let child = state.clone();
    tokio::spawn(async move {
        match run_duel(child.clone(), request).await {
            Ok(()) => set_job(&child, "COMPLETED", None).await,
            Err(message) => {
                error!(%message, "duel watcher failed");
                set_job(&child, "ERROR", Some(bounded_message(&message))).await;
            }
        }
    });
    Ok((StatusCode::ACCEPTED, Json(state.job.read().await.clone())))
}

async fn start_bot_duel(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(mut request): Json<BotDuelStartRequest>,
) -> Result<(StatusCode, Json<JobStatus>), ApiError> {
    require_local_session(&headers, &state)?;
    if request.match_id.trim().is_empty() {
        return Err(ApiError::bad_request("matchId est requis"));
    }
    request.match_id = canonical_uuid(&request.match_id, "matchId")?;
    {
        let mut job = state.job.write().await;
        if !matches!(job.state.as_str(), "IDLE" | "COMPLETED" | "ERROR") {
            return Err(ApiError::conflict("Un duel est déjà suivi"));
        }
        *job = JobStatus {
            match_id: Some(request.match_id.clone()),
            state: "STARTING".into(),
            detail: Some("duel local contre un bot League".into()),
            ..Default::default()
        };
    }
    let child = state.clone();
    tokio::spawn(async move {
        match run_bot_duel(child.clone(), request).await {
            Ok(()) => complete_job(&child).await,
            Err(message) => {
                error!(%message, "bot duel watcher failed");
                set_job(&child, "ERROR", Some(bounded_message(&message))).await;
            }
        }
    });
    Ok((StatusCode::ACCEPTED, Json(state.job.read().await.clone())))
}

async fn run_bot_duel(state: AppState, request: BotDuelStartRequest) -> Result<(), String> {
    let assignment_response =
        send_service_request(&state, "service:match:bot-result", |access_token| {
            state
                .backend_http
                .get(format!(
                    "{}/api/v2/matches/{}/bot-assignment",
                    state.backend_base_url, request.match_id
                ))
                .bearer_auth(access_token)
        })
        .await
        .map_err(|error| error.1)?;
    let assignment: BotMatchAssignment = assignment_response
        .error_for_status()
        .map_err(|error| {
            let status = error
                .status()
                .map_or_else(|| "inconnu".to_owned(), |status| status.to_string());
            format!("Affectation bot refusée ({status})")
        })?
        .json()
        .await
        .map_err(|_| "Affectation bot invalide".to_owned())?;
    if assignment.match_id != request.match_id {
        return Err("Affectation bot incohérente".into());
    }
    let lcu = Lcu::discover().map_err(|error| error.1)?;
    set_job(&state, "LCU_CONNECTED", None).await;
    let summoner = lcu
        .get("/lol-summoner/v1/current-summoner")
        .await
        .map_err(|error| error.1)?;
    let riot_id = riot_id_from_value(&summoner)
        .ok_or("Riot ID complet local introuvable dans le client League")?;
    lcu.post(
        "/lol-lobby/v2/lobby",
        bot_test_lobby(&assignment.lobby_name, &assignment.lobby_password),
    )
    .await
    .map_err(|error| error.1)?;
    set_job(&state, "LOBBY_CREATED", None).await;
    lcu.post(
        "/lol-lobby/v1/lobby/custom/bots",
        json!({
            "botDifficulty": "RSBEGINNER",
            "botUuid": "",
            "championId": 86,
            "position": "TOP",
            "teamId": "200"
        }),
    )
    .await
    .map_err(|error| error.1)?;
    set_job(&state, "BOTH_PRESENT", Some("bot League ajouté".into())).await;
    lcu.post("/lol-lobby/v1/lobby/custom/start-champ-select", json!({}))
        .await
        .map_err(|error| error.1)?;
    set_job(&state, "CHAMP_SELECT_STARTED", None).await;
    let outcome = watch_bot_game(&state, &lcu, &riot_id).await?;
    report_bot_result(&state, &request.match_id, &outcome).await?;
    set_bot_result(&state, outcome.objective, outcome.human_won).await;
    Ok(())
}

async fn status(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<JobStatus>, ApiError> {
    require_local_session(&headers, &state)?;
    Ok(Json(state.job.read().await.clone()))
}

async fn run_duel(state: AppState, request: StartRequest) -> Result<(), String> {
    let base = state.backend_base_url.as_str();
    let assignment_response =
        send_service_request(&state, "service:duel:observe", |access_token| {
            state
                .backend_http
                .get(format!("{base}/api/v2/watchers/duels/{}", request.match_id))
                .bearer_auth(access_token)
                .header("X-Watcher-Token", &request.watcher_token)
        })
        .await
        .map_err(|error| error.1)?;
    let assignment: Assignment = assignment_response
        .error_for_status()
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())?;
    if assignment.match_id != request.match_id {
        return Err("Affectation incohérente".into());
    }
    if simulated() {
        for next in [
            "LCU_CONNECTED",
            if assignment.role == "HOST" {
                "LOBBY_CREATED"
            } else {
                "JOINING"
            },
            if assignment.role == "HOST" {
                "INVITE_SENT"
            } else {
                "JOINED"
            },
            "BOTH_PRESENT",
            "CHAMP_SELECT_STARTED",
            "IN_GAME",
        ] {
            set_job(&state, next, Some("mode simulation locale".into())).await;
            report_state(&state, &assignment.match_id, &request.watcher_token, next).await?;
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        return Ok(());
    }
    let lcu = Lcu::discover().map_err(|e| e.1)?;
    report_state(
        &state,
        &assignment.match_id,
        &request.watcher_token,
        "LCU_CONNECTED",
    )
    .await?;
    set_job(&state, "LCU_CONNECTED", None).await;
    if assignment.role == "HOST" {
        host_lobby(&lcu, &assignment, &state, &request.watcher_token).await?;
    } else {
        guest_lobby(&lcu, &assignment, &state, &request.watcher_token).await?;
    }
    watch_game(&state, &request.watcher_token, &assignment).await?;
    Ok(())
}

async fn host_lobby(
    lcu: &Lcu,
    a: &Assignment,
    state: &AppState,
    token: &str,
) -> Result<(), String> {
    lcu.post(
        "/lol-lobby/v2/lobby",
        human_duel_lobby(&a.lobby_name, &a.lobby_password),
    )
    .await
    .map_err(|e| e.1)?;
    progress(state, &a.match_id, token, "LOBBY_CREATED").await?;
    let id = resolve_summoner_id(lcu, &a.opponent_riot_id).await?;
    lcu.post(
        "/lol-lobby/v2/lobby/invitations",
        json!([{"toSummonerId":id}]),
    )
    .await
    .map_err(|e| e.1)?;
    progress(state, &a.match_id, token, "INVITE_SENT").await?;
    wait_for_expected_players(lcu, &a.own_riot_id, &a.opponent_riot_id).await?;
    progress(state, &a.match_id, token, "BOTH_PRESENT").await?;
    lcu.post("/lol-lobby/v1/lobby/custom/start-champ-select", json!({}))
        .await
        .map_err(|e| e.1)?;
    progress(state, &a.match_id, token, "CHAMP_SELECT_STARTED").await
}

async fn guest_lobby(
    lcu: &Lcu,
    a: &Assignment,
    state: &AppState,
    token: &str,
) -> Result<(), String> {
    progress(state, &a.match_id, token, "JOINING").await?;
    let games = lcu
        .get("/lol-lobby/v1/custom-games")
        .await
        .unwrap_or(json!([]));
    let target = games.as_array().and_then(|items| {
        items
            .iter()
            .find(|v| v.get("lobbyName").and_then(Value::as_str) == Some(&a.lobby_name))
    });
    let joined = if let Some(game) = target {
        if let Some(id) = game.get("id").and_then(Value::as_i64) {
            lcu.post(
                &format!("/lol-lobby/v1/custom-games/{id}/join"),
                json!({"password":a.lobby_password}),
            )
            .await
            .is_ok()
        } else {
            false
        }
    } else {
        false
    };
    if !joined {
        accept_invite(lcu, &a.opponent_riot_id).await?;
    }
    wait_for_expected_players(lcu, &a.own_riot_id, &a.opponent_riot_id).await?;
    progress(state, &a.match_id, token, "JOINED").await
}

async fn accept_invite(lcu: &Lcu, host_riot_id: &str) -> Result<(), String> {
    let expected_summoner_id = resolve_summoner_id(lcu, host_riot_id).await?;
    for _ in 0..60 {
        let invites = lcu
            .get("/lol-lobby/v2/received-invitations")
            .await
            .map_err(|e| e.1)?;
        if let Some(id) = matching_invitation_id(&invites, &expected_summoner_id) {
            lcu.post(
                &format!("/lol-lobby/v2/received-invitations/{id}/accept"),
                json!({}),
            )
            .await
            .map_err(|e| e.1)?;
            return Ok(());
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err("Invitation League introuvable après 60 secondes".into())
}

async fn wait_for_expected_players(lcu: &Lcu, own: &str, opponent: &str) -> Result<(), String> {
    let own_summoner_id = resolve_summoner_id(lcu, own).await?;
    let opponent_summoner_id = resolve_summoner_id(lcu, opponent).await?;
    for _ in 0..120 {
        let lobby = lcu.get("/lol-lobby/v2/lobby").await.map_err(|e| e.1)?;
        if lobby_contains_exact_duel(
            &lobby,
            own,
            opponent,
            &own_summoner_id,
            &opponent_summoner_id,
        )? {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err("Les deux Riot IDs attendus ne sont pas présents dans le lobby".into())
}

async fn resolve_summoner_id(lcu: &Lcu, expected_riot_id: &str) -> Result<String, String> {
    let profile = lcu
        .get(&format!(
            "/lol-summoner/v1/summoners?name={}",
            urlencoding::encode(expected_riot_id)
        ))
        .await
        .map_err(|error| error.1)?;
    let resolved = riot_id_from_value(&profile)
        .ok_or_else(|| "Le client League n'a pas renvoyé un Riot ID complet".to_owned())?;
    if !resolved.eq_ignore_ascii_case(expected_riot_id.trim()) {
        return Err("Le compte League résolu ne correspond pas au Riot ID attendu".into());
    }
    scalar_id(profile.get("summonerId"))
        .ok_or_else(|| "Impossible de résoudre l'identifiant League adverse".to_owned())
}

fn matching_invitation_id(invites: &Value, expected_summoner_id: &str) -> Option<String> {
    invites.as_array()?.iter().find_map(|invite| {
        let sender = scalar_id(invite.get("fromSummonerId"))?;
        if sender != expected_summoner_id {
            return None;
        }
        invite
            .get("invitationId")
            .and_then(Value::as_str)
            .map(str::to_owned)
    })
}

fn lobby_contains_exact_duel(
    lobby: &Value,
    own: &str,
    opponent: &str,
    own_summoner_id: &str,
    opponent_summoner_id: &str,
) -> Result<bool, String> {
    let members = lobby
        .get("members")
        .or_else(|| lobby.pointer("/customGameLobby/members"))
        .and_then(Value::as_array)
        .ok_or_else(|| "Le lobby League ne fournit pas sa liste de membres".to_owned())?;
    if members.len() > 2 {
        return Err("Un joueur inattendu est présent dans le lobby classé".into());
    }
    if members.len() < 2 {
        return Ok(false);
    }
    let mut own_present = false;
    let mut opponent_present = false;
    for member in members {
        let summoner_id = scalar_id(member.get("summonerId"));
        let riot_id = riot_id_from_value(member);
        let matches_own = summoner_id
            .as_deref()
            .is_some_and(|value| value == own_summoner_id)
            || riot_id
                .as_deref()
                .is_some_and(|value| full_riot_id_eq(value, own));
        let matches_opponent = summoner_id
            .as_deref()
            .is_some_and(|value| value == opponent_summoner_id)
            || riot_id
                .as_deref()
                .is_some_and(|value| full_riot_id_eq(value, opponent));
        if matches_own == matches_opponent {
            return Err("Le lobby contient un membre dont l'identité exacte est inattendue".into());
        }
        own_present |= matches_own;
        opponent_present |= matches_opponent;
    }
    Ok(own_present && opponent_present)
}

fn scalar_id(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(value) if !value.trim().is_empty() => Some(value.trim().to_owned()),
        Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}

async fn watch_game(state: &AppState, token: &str, a: &Assignment) -> Result<(), String> {
    let live = LiveClient::new()?;
    let mut in_game_reported = false;
    let mut cs = HumanCsTracker::default();
    for _ in 0..7200 {
        let players = live.get(LiveEndpoint::PlayerList).await.ok();
        if let Ok(data) = live.get(LiveEndpoint::EventData).await {
            if mark_once(&mut in_game_reported) {
                report_state(state, &a.match_id, token, "IN_GAME").await?;
            }
            if let Some(players) = players.as_ref()
                && let Some((objective, winner)) =
                    find_winner(&data, players, &a.own_riot_id, &a.opponent_riot_id)?
            {
                report_observation(state, token, a, objective, &winner).await?;
                return Ok(());
            }
        }
        if let Some(players) = players.as_ref()
            && let Some(winner) = cs.observe(players, &a.own_riot_id, &a.opponent_riot_id)?
        {
            report_observation(state, token, a, "FIRST_TO_100_CS", &winner).await?;
            return Ok(());
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err("Aucun objectif de victoire détecté avant expiration du watcher".into())
}

fn mark_once(reported: &mut bool) -> bool {
    if *reported {
        false
    } else {
        *reported = true;
        true
    }
}

async fn watch_bot_game(
    state: &AppState,
    lcu: &Lcu,
    own_game_name: &str,
) -> Result<BotOutcome, String> {
    let live = LiveClient::new()?;
    let mut entered_game = false;
    let mut cs = BotCsTracker::default();
    for _ in 0..7200 {
        if let Ok(phase) = lcu.get("/lol-gameflow/v1/gameflow-phase").await
            && let Some(phase) = phase.as_str()
        {
            if matches!(phase, "InProgress" | "Reconnect") {
                if !entered_game {
                    entered_game = true;
                    set_job(state, "IN_GAME", Some("objectifs 1v1 surveillés".into())).await;
                }
            } else if entered_game
                && matches!(
                    phase,
                    "None" | "PreEndOfGame" | "EndOfGame" | "WaitingForStats"
                )
            {
                return Err("La partie s'est terminée sans objectif 1v1 détecté".into());
            }
        }

        if entered_game {
            let players = live.get(LiveEndpoint::PlayerList).await.ok();

            if let Ok(events) = live.get(LiveEndpoint::EventData).await
                && let Some((objective, won)) =
                    find_bot_winner(&events, own_game_name, players.as_ref())?
            {
                return Ok(BotOutcome {
                    objective,
                    human_won: won,
                });
            }

            if let Some(players) = players.as_ref()
                && let Some(won) = cs.observe(players, own_game_name)?
            {
                return Ok(BotOutcome {
                    objective: "FIRST_TO_100_CS",
                    human_won: won,
                });
            }
        }

        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err("Aucun objectif 1v1 détecté avant expiration du watcher".into())
}

fn find_winner(
    events: &Value,
    players: &Value,
    own: &str,
    opponent: &str,
) -> Result<Option<(&'static str, String)>, String> {
    let Some(events) = events.get("Events").and_then(Value::as_array) else {
        return Ok(None);
    };
    for event in events {
        let Some(name) = event.get("EventName").and_then(Value::as_str) else {
            continue;
        };
        let objective = match name {
            "ChampionKill" => "FIRST_BLOOD",
            "TurretKilled" => "FIRST_TOWER",
            _ => continue,
        };
        let killer = event
            .get("KillerName")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if let Some(resolved) = resolve_actor_riot_id(players, killer, &[own, opponent])? {
            return Ok(Some((objective, resolved)));
        }
    }
    Ok(None)
}

#[derive(Default)]
struct HumanCsTracker {
    previous_own: Option<i64>,
    previous_opponent: Option<i64>,
}

impl HumanCsTracker {
    fn observe(
        &mut self,
        players: &Value,
        own: &str,
        opponent: &str,
    ) -> Result<Option<String>, String> {
        let own_cs = exact_player(players, own).map(creep_score)?;
        let opponent_cs = exact_player(players, opponent).map(creep_score)?;
        let own_crossed = own_cs >= 100 && self.previous_own.is_none_or(|previous| previous < 100);
        let opponent_crossed =
            opponent_cs >= 100 && self.previous_opponent.is_none_or(|previous| previous < 100);
        self.previous_own = Some(own_cs);
        self.previous_opponent = Some(opponent_cs);
        match (own_crossed, opponent_crossed) {
            (true, true) => Err("Résultat 100 CS ambigu : les deux joueurs ont franchi le seuil entre deux observations".into()),
            (true, false) => Ok(Some(own.to_owned())),
            (false, true) => Ok(Some(opponent.to_owned())),
            (false, false) => Ok(None),
        }
    }
}

fn find_bot_winner(
    events: &Value,
    own: &str,
    players: Option<&Value>,
) -> Result<Option<(&'static str, bool)>, String> {
    let Some(events) = events.get("Events").and_then(Value::as_array) else {
        return Ok(None);
    };
    for event in events {
        let Some(event_name) = event.get("EventName").and_then(Value::as_str) else {
            continue;
        };
        let objective = match event_name {
            "ChampionKill" => "FIRST_BLOOD",
            "TurretKilled" => "FIRST_TOWER",
            _ => continue,
        };
        let killer = event
            .get("KillerName")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if killer.is_empty() {
            continue;
        }
        if let Some(players) = players {
            if let Some(won) = resolve_bot_actor(players, killer, own)? {
                return Ok(Some((objective, won)));
            }
        } else if full_riot_id_eq(killer, own) {
            return Ok(Some((objective, true)));
        }
    }
    Ok(None)
}

#[derive(Default)]
struct BotCsTracker {
    previous_human: Option<i64>,
    previous_bot: Option<i64>,
}

impl BotCsTracker {
    fn observe(&mut self, players: &Value, own: &str) -> Result<Option<bool>, String> {
        let roster = players
            .as_array()
            .ok_or_else(|| "Roster Live Client invalide".to_owned())?;
        let local = exact_player(players, own)?;
        let opponents = roster
            .iter()
            .filter(|player| !std::ptr::eq(*player, local))
            .collect::<Vec<_>>();
        if opponents.len() != 1 {
            return Err("Le duel bot ne contient pas exactement un adversaire".into());
        }
        let human_cs = creep_score(local);
        let bot_cs = creep_score(opponents[0]);
        let human_crossed =
            human_cs >= 100 && self.previous_human.is_none_or(|previous| previous < 100);
        let bot_crossed = bot_cs >= 100 && self.previous_bot.is_none_or(|previous| previous < 100);
        self.previous_human = Some(human_cs);
        self.previous_bot = Some(bot_cs);
        match (human_crossed, bot_crossed) {
            (true, true) => Err("Résultat 100 CS ambigu : les deux joueurs ont franchi le seuil entre deux observations".into()),
            (true, false) => Ok(Some(true)),
            (false, true) => Ok(Some(false)),
            (false, false) => Ok(None),
        }
    }
}

fn exact_player<'a>(players: &'a Value, expected: &str) -> Result<&'a Value, String> {
    if !is_full_riot_id(expected) {
        return Err("Un Riot ID complet Pseudo#TAG est requis".into());
    }
    let matches = players
        .as_array()
        .ok_or_else(|| "Roster Live Client invalide".to_owned())?
        .iter()
        .filter(|player| {
            riot_id_from_value(player)
                .is_some_and(|riot_id| riot_id.eq_ignore_ascii_case(expected.trim()))
        })
        .collect::<Vec<_>>();
    match matches.as_slice() {
        [player] => Ok(*player),
        [] => Err(format!("Riot ID attendu absent du roster : {expected}")),
        _ => Err(format!("Riot ID ambigu dans le roster : {expected}")),
    }
}

fn creep_score(player: &Value) -> i64 {
    player
        .get("scores")
        .and_then(|scores| scores.get("creepScore"))
        .and_then(Value::as_i64)
        .unwrap_or(0)
}

fn resolve_actor_riot_id(
    players: &Value,
    actor: &str,
    expected: &[&str],
) -> Result<Option<String>, String> {
    let actor = actor.trim();
    if actor.is_empty() {
        return Ok(None);
    }
    if is_full_riot_id(actor) {
        return Ok(expected
            .iter()
            .find(|riot_id| full_riot_id_eq(actor, riot_id))
            .map(|riot_id| (*riot_id).to_owned()));
    }
    let roster = players
        .as_array()
        .ok_or_else(|| "Roster Live Client invalide".to_owned())?;
    let mut candidates = roster
        .iter()
        .filter(|player| actor_matches_player_alias(player, actor))
        .filter_map(riot_id_from_value)
        .filter(|riot_id| expected.iter().any(|value| full_riot_id_eq(riot_id, value)))
        .collect::<Vec<_>>();
    candidates.sort_by_key(|value| value.to_lowercase());
    candidates.dedup_by(|left, right| left.eq_ignore_ascii_case(right));
    match candidates.as_slice() {
        [] => Ok(None),
        [riot_id] => Ok(Some(riot_id.clone())),
        _ => Err(format!("Identité Riot ambiguë pour l'acteur {actor}")),
    }
}

fn resolve_bot_actor(players: &Value, actor: &str, own: &str) -> Result<Option<bool>, String> {
    let roster = players
        .as_array()
        .ok_or_else(|| "Roster Live Client invalide".to_owned())?;
    let local = exact_player(players, own)?;
    let matches = roster
        .iter()
        .filter(|player| actor_matches_player_alias(player, actor))
        .collect::<Vec<_>>();
    match matches.as_slice() {
        [] => Ok(None),
        [player] => Ok(Some(std::ptr::eq(*player, local))),
        _ => Err(format!(
            "Identité ambiguë pour l'acteur Live Client {actor}"
        )),
    }
}

fn riot_id_from_value(value: &Value) -> Option<String> {
    if let Some(riot_id) = value.get("riotId").and_then(Value::as_str)
        && is_full_riot_id(riot_id)
    {
        return Some(riot_id.trim().to_owned());
    }
    let game_name = value
        .get("riotIdGameName")
        .or_else(|| value.get("gameName"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty());
    let tag_line = value
        .get("riotIdTagLine")
        .or_else(|| value.get("tagLine"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty());
    if let (Some(game_name), Some(tag_line)) = (game_name, tag_line) {
        return Some(format!("{game_name}#{tag_line}"));
    }
    value
        .get("summonerName")
        .and_then(Value::as_str)
        .filter(|value| is_full_riot_id(value))
        .map(|value| value.trim().to_owned())
}

fn player_aliases(player: &Value) -> Vec<&str> {
    ["riotId", "riotIdGameName", "summonerName", "championName"]
        .iter()
        .filter_map(|field| player.get(field).and_then(Value::as_str))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .collect()
}

fn actor_matches_player_alias(player: &Value, actor: &str) -> bool {
    let actor = actor.trim();
    player_aliases(player)
        .iter()
        .any(|alias| alias.eq_ignore_ascii_case(actor))
        || riot_id_from_value(player)
            .and_then(|riot_id| {
                riot_id
                    .rsplit_once('#')
                    .map(|(game_name, _)| game_name.to_owned())
            })
            .is_some_and(|game_name| game_name.eq_ignore_ascii_case(actor))
}

fn is_full_riot_id(value: &str) -> bool {
    value
        .trim()
        .rsplit_once('#')
        .is_some_and(|(game_name, tag_line)| !game_name.is_empty() && !tag_line.is_empty())
}

fn full_riot_id_eq(observed: &str, expected: &str) -> bool {
    is_full_riot_id(observed)
        && is_full_riot_id(expected)
        && observed.trim().eq_ignore_ascii_case(expected.trim())
}

async fn report_observation(
    state: &AppState,
    token: &str,
    a: &Assignment,
    objective: &str,
    winner: &str,
) -> Result<(), String> {
    let response = send_service_request(state, "service:duel:observe", |access_token| {
        state
            .backend_http
            .post(format!(
                "{}/api/v2/watchers/duels/{}/observations",
                state.backend_base_url, a.match_id
            ))
            .bearer_auth(access_token)
            .header("X-Watcher-Token", token)
            .json(&json!({"objective":objective,"winnerRiotId":winner,"observedAt":now_iso()}))
    })
    .await
    .map_err(|error| error.1)?;
    response
        .error_for_status()
        .map_err(|error| error.to_string())?;
    Ok(())
}

async fn progress(state: &AppState, match_id: &str, token: &str, next: &str) -> Result<(), String> {
    set_job(state, next, None).await;
    report_state(state, match_id, token, next).await
}
async fn report_state(
    state: &AppState,
    match_id: &str,
    token: &str,
    next: &str,
) -> Result<(), String> {
    let response = send_service_request(state, "service:duel:observe", |access_token| {
        state
            .backend_http
            .put(format!(
                "{}/api/v2/watchers/duels/{match_id}/state",
                state.backend_base_url
            ))
            .bearer_auth(access_token)
            .header("X-Watcher-Token", token)
            .json(&json!({"state":next}))
    })
    .await
    .map_err(|error| error.1)?;
    response
        .error_for_status()
        .map_err(|error| error.to_string())?;
    Ok(())
}

async fn report_bot_result(
    state: &AppState,
    match_id: &str,
    outcome: &BotOutcome,
) -> Result<(), String> {
    let response = send_service_request(state, "service:match:bot-result", |access_token| {
        state
            .backend_http
            .post(format!(
                "{}/api/v2/matches/{match_id}/bot-result",
                state.backend_base_url
            ))
            .bearer_auth(access_token)
            .json(&BotMatchResult {
                human_won: outcome.human_won,
                objective: outcome.objective,
                observed_at: now_iso(),
            })
    })
    .await
    .map_err(|error| error.1)?;
    response
        .error_for_status()
        .map_err(|error| format!("Résultat bot refusé par le backend ({error})"))?;
    Ok(())
}
async fn set_job(state: &AppState, next: &str, detail: Option<String>) {
    let mut job = state.job.write().await;
    job.state = next.into();
    job.detail = detail;
}

async fn set_bot_result(state: &AppState, objective: &str, won: bool) {
    let mut job = state.job.write().await;
    job.state = "OBJECTIVE_RECORDED".into();
    job.outcome = Some(if won { "VICTORY" } else { "DEFEAT" }.into());
    job.objective = Some(objective.into());
    job.detail = Some(format!(
        "{} détecté · {} locale",
        objective,
        if won { "victoire" } else { "défaite" }
    ));
}

async fn complete_job(state: &AppState) {
    let mut job = state.job.write().await;
    job.state = "COMPLETED".into();
    if job.detail.is_none() {
        job.detail = Some("lobby League prêt".into());
    }
}

impl Lcu {
    fn discover() -> Result<Self, ApiError> {
        let http = riot_local_client()?;
        if let (Ok(port), Ok(password)) = (
            env::var("SHOWDOWN_LCU_PORT"),
            env::var("SHOWDOWN_LCU_TOKEN"),
        ) {
            return Ok(Self {
                base: lcu_loopback_base(&port)?,
                password,
                http,
            });
        }
        let path = find_lcu_lockfile()?;
        let raw = fs::read_to_string(&path).map_err(|_| {
            ApiError::bad_gateway(&format!("Client League introuvable ({})", path.display()))
        })?;
        let parts: Vec<&str> = raw.trim().split(':').collect();
        if parts.len() != 5 {
            return Err(ApiError::bad_gateway("Lockfile League invalide"));
        }
        Ok(Self {
            base: lcu_loopback_base(parts[2])?,
            password: parts[3].into(),
            http,
        })
    }
    async fn get(&self, path: &str) -> Result<Value, ApiError> {
        self.request(self.http.get(format!("{}{}", self.base, path)))
            .await
    }
    async fn post(&self, path: &str, body: Value) -> Result<Value, ApiError> {
        self.request(self.http.post(format!("{}{}", self.base, path)).json(&body))
            .await
    }
    async fn request(&self, builder: reqwest::RequestBuilder) -> Result<Value, ApiError> {
        let response = builder
            .header(
                "Authorization",
                format!(
                    "Basic {}",
                    STANDARD.encode(format!("riot:{}", self.password))
                ),
            )
            .send()
            .await
            .map_err(|e| ApiError::bad_gateway(&e.to_string()))?;
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        if !status.is_success() {
            let detail = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|value| {
                    value
                        .get("message")
                        .and_then(Value::as_str)
                        .map(str::to_owned)
                })
                .map(|message| bounded_message(&message))
                .filter(|message| !message.is_empty());
            return Err(ApiError::bad_gateway(&match detail {
                Some(detail) => format!("LCU {status}: {detail}"),
                None => format!("LCU {status}"),
            }));
        }
        if body.is_empty() {
            Ok(json!({}))
        } else {
            serde_json::from_str(&body).map_err(|e| ApiError::bad_gateway(&e.to_string()))
        }
    }
}

impl LiveClient {
    fn new() -> Result<Self, String> {
        riot_local_client()
            .map(|http| Self { http })
            .map_err(|error| error.1)
    }

    async fn get(&self, endpoint: LiveEndpoint) -> Result<Value, String> {
        self.http
            .get(endpoint.url())
            .send()
            .await
            .map_err(|error| error.to_string())?
            .error_for_status()
            .map_err(|error| error.to_string())?
            .json()
            .await
            .map_err(|error| error.to_string())
    }
}

impl LiveEndpoint {
    fn url(self) -> &'static str {
        match self {
            Self::EventData => "https://127.0.0.1:2999/liveclientdata/eventdata",
            Self::PlayerList => "https://127.0.0.1:2999/liveclientdata/playerlist",
        }
    }
}

fn riot_local_client() -> Result<Client, ApiError> {
    Client::builder()
        .danger_accept_invalid_certs(true)
        .https_only(true)
        .no_proxy()
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|_| ApiError::bad_gateway("Impossible d'initialiser le client Riot local"))
}

fn lcu_loopback_base(port: &str) -> Result<String, ApiError> {
    let port = port
        .trim()
        .parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| ApiError::bad_gateway("Port LCU invalide"))?;
    Ok(format!("https://127.0.0.1:{port}"))
}

fn normalize_backend_base(value: &str) -> Result<String, String> {
    let url = Url::parse(value.trim()).map_err(|_| "URL backend invalide".to_owned())?;
    if !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !matches!(url.path(), "" | "/")
    {
        return Err("URL backend non autorisée".into());
    }
    let host = url
        .host_str()
        .ok_or_else(|| "Hôte backend manquant".to_owned())?;
    let loopback = host.eq_ignore_ascii_case("localhost")
        || host
            .parse::<IpAddr>()
            .is_ok_and(|address| address.is_loopback());
    if url.scheme() == "http" && !loopback {
        return Err("HTTPS est requis pour un backend distant".into());
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}

fn configured_watcher_address() -> Result<SocketAddr, String> {
    validate_watcher_address(
        &env::var("SHOWDOWN_WATCHER_ADDRESS").unwrap_or_else(|_| "127.0.0.1:43991".into()),
    )
}

fn validate_watcher_address(value: &str) -> Result<SocketAddr, String> {
    let address = value
        .parse::<SocketAddr>()
        .map_err(|_| "adresse d'écoute invalide".to_owned())?;
    if !address.ip().is_loopback() {
        return Err("le Watcher doit écouter exclusivement sur une adresse loopback".into());
    }
    Ok(address)
}

fn configured_web_origins() -> Result<Vec<String>, String> {
    let environment =
        env::var("SHOWDOWN_WATCHER_ENVIRONMENT").unwrap_or_else(|_| "development".into());
    let configured = env::var("SHOWDOWN_WEB_ORIGINS").unwrap_or_default();
    web_origins(&environment, &configured)
}

fn production_mode() -> bool {
    env::var("SHOWDOWN_WATCHER_ENVIRONMENT")
        .is_ok_and(|value| value.eq_ignore_ascii_case("production"))
}

fn validate_watcher_credential(
    production: bool,
    client_id: &str,
    secret: &str,
) -> Result<(), String> {
    if !production {
        return Ok(());
    }
    if !client_id.starts_with("pinkward-watcher-installation-")
        || client_id.len() > 96
        || !client_id
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '-' | '_'))
    {
        return Err("un client OAuth propre à l'installation est requis en production".into());
    }
    if secret.len() < 32 {
        return Err("le secret OAuth d'installation doit contenir au moins 32 caractères".into());
    }
    Ok(())
}

fn web_origins(environment: &str, configured: &str) -> Result<Vec<String>, String> {
    let production = environment.eq_ignore_ascii_case("production");
    let mut origins = configured
        .split(',')
        .map(str::trim)
        .filter(|origin| !origin.is_empty())
        .map(str::to_owned)
        .collect::<Vec<_>>();
    if production {
        if origins.is_empty() {
            return Err("SHOWDOWN_WEB_ORIGINS est obligatoire en production".into());
        }
        if origins.iter().any(|origin| {
            Url::parse(origin).map_or(true, |url| {
                url.scheme() != "https"
                    || url.host_str().is_none()
                    || url.path() != "/"
                    || url.query().is_some()
                    || url.fragment().is_some()
            })
        }) {
            return Err(
                "les origines de production doivent être des origines HTTPS exactes".into(),
            );
        }
    } else if origins.is_empty() {
        origins = vec![
            "http://localhost:3000".to_owned(),
            "http://127.0.0.1:3000".to_owned(),
            "http://localhost:8088".to_owned(),
            "http://127.0.0.1:8088".to_owned(),
        ];
    }
    origins.sort();
    origins.dedup();
    Ok(origins)
}

fn canonical_uuid(value: &str, field: &str) -> Result<String, ApiError> {
    uuid::Uuid::parse_str(value.trim())
        .map(|value| value.to_string())
        .map_err(|_| ApiError::bad_request(&format!("{field} invalide")))
}

fn find_lcu_lockfile() -> Result<PathBuf, ApiError> {
    if let Ok(configured) = env::var("SHOWDOWN_LCU_LOCKFILE") {
        let path = PathBuf::from(configured);
        return path.exists().then_some(path).ok_or_else(|| {
            ApiError::bad_gateway(
                "Le chemin SHOWDOWN_LCU_LOCKFILE ne pointe pas vers un client League actif",
            )
        });
    }

    for drive in b'A'..=b'Z' {
        let drive = drive as char;
        for relative in [
            r"Riot Games\League of Legends\lockfile",
            r"Program Files\Riot Games\League of Legends\lockfile",
            r"Program Files (x86)\Riot Games\League of Legends\lockfile",
        ] {
            let path = PathBuf::from(format!(r"{drive}:\{relative}"));
            if path.exists() {
                return Ok(path);
            }
        }
    }

    Err(ApiError::bad_gateway(
        "Client League introuvable. Ouvre League ou configure SHOWDOWN_LCU_LOCKFILE",
    ))
}

fn bot_test_lobby(lobby_name: &str, lobby_password: &str) -> Value {
    custom_lobby(lobby_name, lobby_password, CustomLobbyPreset::BOT_TEST)
}

fn human_duel_lobby(lobby_name: &str, lobby_password: &str) -> Value {
    custom_lobby(lobby_name, lobby_password, CustomLobbyPreset::HUMAN_DUEL)
}

#[derive(Clone, Copy)]
struct CustomLobbyPreset {
    queue_id: i32,
    map_id: i32,
    game_mode: &'static str,
    team_size: i32,
    max_player_count: i32,
    spectator_policy: &'static str,
    hide_publicly: bool,
}

impl CustomLobbyPreset {
    const BOT_TEST: Self = Self {
        queue_id: 3100,
        map_id: 11,
        game_mode: "CLASSIC",
        team_size: 5,
        max_player_count: 10,
        spectator_policy: "NotAllowed",
        hide_publicly: true,
    };

    const HUMAN_DUEL: Self = Self {
        queue_id: 3200,
        map_id: 12,
        game_mode: "ARAM",
        team_size: 1,
        max_player_count: 2,
        spectator_policy: "NotAllowed",
        hide_publicly: true,
    };
}

fn custom_lobby(lobby_name: &str, lobby_password: &str, preset: CustomLobbyPreset) -> Value {
    json!({
        "queueId": preset.queue_id,
        "customGameLobby": {
            "lobbyName": lobby_name,
            "lobbyPassword": lobby_password,
            "configuration": {
                "mapId": preset.map_id,
                "gameMode": preset.game_mode,
                "mutators": { "id": preset.queue_id },
                "gameTypeConfig": { "id": 0 },
                "spectatorPolicy": preset.spectator_policy,
                "teamSize": preset.team_size,
                "maxPlayerCount": preset.max_player_count,
                "tournamentGameMode": "",
                "tournamentPassbackUrl": "",
                "tournamentPassbackDataPacket": "",
                "gameServerRegion": "",
                "spectatorDelayEnabled": false,
                "hidePublicly": preset.hide_publicly,
                "aramMapMutator": ""
            },
            "teamOne": [],
            "teamTwo": [],
            "spectators": [],
            "practiceGameRewardsDisabledReasons": [],
            "gameId": 0
        }
    })
}

fn split_riot_id(value: &str, puuid: &str) -> Result<RiotIdentity, ApiError> {
    let (game, tag) = value
        .trim()
        .rsplit_once('#')
        .ok_or_else(|| ApiError::bad_request("SHOWDOWN_RIOT_ID doit être Pseudo#TAG"))?;
    if puuid.trim().len() < 16 {
        return Err(ApiError::bad_request(
            "SHOWDOWN_RIOT_PUUID doit contenir au moins 16 caractères",
        ));
    }
    Ok(RiotIdentity {
        puuid: puuid.trim().into(),
        game_name: game.into(),
        tag_line: tag.into(),
        riot_id: value.trim().into(),
        profile_icon_id: 0,
        summoner_level: 0,
    })
}
fn simulated() -> bool {
    env::var("SHOWDOWN_WATCHER_SIMULATE").is_ok_and(|v| v.eq_ignore_ascii_case("true"))
}
fn now_iso() -> String {
    chrono::Utc::now().to_rfc3339()
}

fn bounded_message(value: &str) -> String {
    value
        .chars()
        .filter(|character| !character.is_control() || *character == ' ')
        .take(240)
        .collect::<String>()
        .trim()
        .to_owned()
}

#[derive(Debug)]
struct ApiError(StatusCode, String);
impl ApiError {
    fn bad_request(v: &str) -> Self {
        Self(StatusCode::BAD_REQUEST, v.into())
    }
    fn conflict(v: &str) -> Self {
        Self(StatusCode::CONFLICT, v.into())
    }
    fn forbidden(v: &str) -> Self {
        Self(StatusCode::FORBIDDEN, v.into())
    }
    fn bad_gateway(v: &str) -> Self {
        Self(StatusCode::BAD_GATEWAY, v.into())
    }
    fn service_unavailable(v: &str) -> Self {
        Self(StatusCode::SERVICE_UNAVAILABLE, v.into())
    }
}
impl axum::response::IntoResponse for ApiError {
    fn into_response(self) -> axum::response::Response {
        (self.0, Json(json!({"error":self.1}))).into_response()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};

    fn secured_state() -> AppState {
        secured_state_with_base("http://localhost:8088")
    }

    fn secured_state_with_base(base: &str) -> AppState {
        AppState {
            job: Arc::new(RwLock::new(JobStatus::default())),
            backend_http: BackendHttp::new().unwrap(),
            backend_base_url: Arc::new(base.into()),
            watcher_client_id: Arc::new("pinkward-watcher".into()),
            watcher_client_secret: Arc::new("test-secret".into()),
            service_tokens: Arc::new(Mutex::new(HashMap::new())),
            session_token: Arc::new("test-local-token".into()),
            allowed_origins: Arc::new(vec!["http://localhost:3000".into()]),
        }
    }

    async fn spawn_oauth_server(
        token_ttl: u64,
        reject_first_resource_token: bool,
    ) -> (
        String,
        Arc<AtomicUsize>,
        Arc<AtomicUsize>,
        tokio::task::JoinHandle<()>,
    ) {
        let token_requests = Arc::new(AtomicUsize::new(0));
        let resource_requests = Arc::new(AtomicUsize::new(0));
        let token_counter = token_requests.clone();
        let resource_counter = resource_requests.clone();
        let app = Router::new()
            .route(
                "/oauth2/token",
                post(move || {
                    let token_number = token_counter.fetch_add(1, AtomicOrdering::SeqCst) + 1;
                    async move {
                        Json(json!({
                            "access_token": format!("token-{token_number}"),
                            "expires_in": token_ttl
                        }))
                    }
                }),
            )
            .route(
                "/resource",
                get(move |headers: HeaderMap| {
                    let request_number = resource_counter.fetch_add(1, AtomicOrdering::SeqCst) + 1;
                    async move {
                        let bearer = headers
                            .get("authorization")
                            .and_then(|value| value.to_str().ok())
                            .unwrap_or_default();
                        if reject_first_resource_token
                            && request_number == 1
                            && bearer == "Bearer token-1"
                        {
                            StatusCode::UNAUTHORIZED
                        } else {
                            StatusCode::OK
                        }
                    }
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        (
            format!("http://{address}"),
            token_requests,
            resource_requests,
            task,
        )
    }

    #[test]
    fn rejects_a_mutating_request_without_the_ephemeral_local_token() {
        let error = require_local_session(&HeaderMap::new(), &secured_state()).unwrap_err();
        assert_eq!(error.0, StatusCode::FORBIDDEN);
    }

    #[test]
    fn accepts_a_mutating_request_with_the_ephemeral_local_token() {
        let mut headers = HeaderMap::new();
        headers.insert(
            "x-showdown-watcher-token",
            HeaderValue::from_static("test-local-token"),
        );
        assert!(require_local_session(&headers, &secured_state()).is_ok());
    }

    #[tokio::test]
    async fn only_an_explicit_local_web_origin_can_create_a_session() {
        let mut forbidden = HeaderMap::new();
        forbidden.insert("origin", HeaderValue::from_static("http://evil.test"));
        assert_eq!(
            create_session(State(secured_state()), forbidden)
                .await
                .unwrap_err()
                .0,
            StatusCode::FORBIDDEN
        );
        let mut allowed = HeaderMap::new();
        allowed.insert("origin", HeaderValue::from_static("http://localhost:3000"));
        assert_eq!(
            create_session(State(secured_state()), allowed)
                .await
                .unwrap()
                .0
                .token,
            "test-local-token"
        );
    }

    #[tokio::test]
    async fn protects_watcher_status_with_the_local_session() {
        assert_eq!(
            status(State(secured_state()), HeaderMap::new())
                .await
                .unwrap_err()
                .0,
            StatusCode::FORBIDDEN
        );
    }

    #[tokio::test]
    async fn renews_an_expiring_service_token() {
        let (base, token_requests, _, server) = spawn_oauth_server(1, false).await;
        let state = secured_state_with_base(&base);
        assert_eq!(
            service_access_token(&state, "service:duel:observe")
                .await
                .unwrap(),
            "token-1"
        );
        tokio::time::sleep(Duration::from_millis(1100)).await;
        assert_eq!(
            service_access_token(&state, "service:duel:observe")
                .await
                .unwrap(),
            "token-2"
        );
        assert_eq!(token_requests.load(AtomicOrdering::SeqCst), 2);
        server.abort();
    }

    #[tokio::test]
    async fn refreshes_once_after_an_unauthorized_service_response() {
        let (base, token_requests, resource_requests, server) = spawn_oauth_server(120, true).await;
        let state = secured_state_with_base(&base);
        let response = send_service_request(&state, "service:duel:observe", |access_token| {
            state
                .backend_http
                .get(format!("{}/resource", state.backend_base_url))
                .bearer_auth(access_token)
        })
        .await
        .unwrap();
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        assert_eq!(token_requests.load(AtomicOrdering::SeqCst), 2);
        assert_eq!(resource_requests.load(AtomicOrdering::SeqCst), 2);
        server.abort();
    }

    #[test]
    fn allows_plain_http_only_for_a_loopback_backend() {
        assert_eq!(
            normalize_backend_base("http://localhost:8088/").unwrap(),
            "http://localhost:8088"
        );
        assert_eq!(
            normalize_backend_base("http://127.0.0.1:8088").unwrap(),
            "http://127.0.0.1:8088"
        );
        assert!(normalize_backend_base("http://showdown.example").is_err());
        assert_eq!(
            normalize_backend_base("https://showdown.example").unwrap(),
            "https://showdown.example"
        );
    }

    #[test]
    fn refuses_backend_credentials_paths_and_redirect_targets() {
        assert!(normalize_backend_base("https://user:secret@showdown.example").is_err());
        assert!(normalize_backend_base("https://showdown.example/api").is_err());
        assert!(normalize_backend_base("https://showdown.example?next=http://evil.test").is_err());
    }

    #[test]
    fn confines_lcu_and_live_client_urls_to_loopback() {
        assert_eq!(
            lcu_loopback_base("53921").unwrap(),
            "https://127.0.0.1:53921"
        );
        assert!(lcu_loopback_base("443@evil.test").is_err());
        assert!(lcu_loopback_base("0").is_err());
        assert_eq!(
            LiveEndpoint::EventData.url(),
            "https://127.0.0.1:2999/liveclientdata/eventdata"
        );
        assert_eq!(
            LiveEndpoint::PlayerList.url(),
            "https://127.0.0.1:2999/liveclientdata/playerlist"
        );
    }

    #[test]
    fn simulated_identity_requires_a_stable_puuid() {
        assert!(split_riot_id("Claude Code#JAVA", "too-short").is_err());
        let identity = split_riot_id("Claude Code#JAVA", "stable-test-puuid-1234").unwrap();
        assert_eq!(identity.puuid, "stable-test-puuid-1234");
        assert_eq!(identity.riot_id, "Claude Code#JAVA");
    }

    #[test]
    fn refuses_path_injection_in_match_identifiers() {
        assert!(canonical_uuid("../oauth2/token", "matchId").is_err());
        assert_eq!(
            canonical_uuid("A1D6509E-8518-4C23-927B-72A08D304F95", "matchId").unwrap(),
            "a1d6509e-8518-4c23-927b-72a08d304f95"
        );
    }

    #[test]
    fn detects_the_first_duel_objective_in_event_order() {
        let data = json!({"Events":[
            {"EventName":"GameStart"},
            {"EventName":"ChampionKill","KillerName":"Claude Code"},
            {"EventName":"TurretKilled","KillerName":"Codex"}
        ]});
        let players = json!([
            {"riotIdGameName":"Claude Code","riotIdTagLine":"JAVA","championName":"Draven"},
            {"riotIdGameName":"Codex","riotIdTagLine":"GPT","championName":"Garen"}
        ]);
        assert_eq!(
            find_winner(&data, &players, "Claude Code#JAVA", "Codex#GPT"),
            Ok(Some(("FIRST_BLOOD", "Claude Code#JAVA".into())))
        );
    }

    #[test]
    fn detects_first_player_to_one_hundred_cs() {
        let before = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":99}},
            {"riotId":"Codex#GPT","scores":{"creepScore":99}}
        ]);
        let after = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":99}},
            {"riotId":"Codex#GPT","scores":{"creepScore":100}}
        ]);
        let mut tracker = HumanCsTracker::default();
        assert_eq!(
            tracker.observe(&before, "Claude Code#JAVA", "Codex#GPT"),
            Ok(None)
        );
        assert_eq!(
            tracker.observe(&after, "Claude Code#JAVA", "Codex#GPT"),
            Ok(Some("Codex#GPT".into()))
        );
    }

    #[test]
    fn rejects_simultaneous_one_hundred_cs_crossings() {
        let before = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":99}},
            {"riotId":"Codex#GPT","scores":{"creepScore":98}}
        ]);
        let after = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":101}},
            {"riotId":"Codex#GPT","scores":{"creepScore":100}}
        ]);
        let mut tracker = HumanCsTracker::default();
        assert_eq!(
            tracker.observe(&before, "Claude Code#JAVA", "Codex#GPT"),
            Ok(None)
        );
        assert!(
            tracker
                .observe(&after, "Claude Code#JAVA", "Codex#GPT")
                .is_err()
        );
    }

    #[test]
    fn distinguishes_equal_game_names_with_different_tags() {
        let players = json!([
            {"riotId":"Alexis#EUW","championName":"Ahri"},
            {"riotId":"Alexis#ABC","championName":"Garen"}
        ]);
        assert_eq!(
            resolve_actor_riot_id(&players, "Garen", &["Alexis#EUW", "Alexis#ABC"]),
            Ok(Some("Alexis#ABC".into()))
        );
        assert!(resolve_actor_riot_id(&players, "Alexis", &["Alexis#EUW", "Alexis#ABC"]).is_err());
        assert!(!full_riot_id_eq("Alexis#EUW", "Alexis#ABC"));
    }

    #[test]
    fn builds_the_lcu_bot_test_preset() {
        let lobby = bot_test_lobby("SWD-TEST", "SECRET");
        assert_eq!(lobby["queueId"], 3100);
        assert_eq!(lobby["customGameLobby"]["configuration"]["mapId"], 11);
        assert_eq!(lobby["customGameLobby"]["configuration"]["teamSize"], 5);
    }

    #[test]
    fn builds_the_lcu_human_duel_preset() {
        let lobby = human_duel_lobby("SWD-DUEL", "SECRET");
        assert_eq!(lobby["queueId"], 3200);
        assert_eq!(lobby["customGameLobby"]["configuration"]["mapId"], 12);
        assert_eq!(lobby["customGameLobby"]["configuration"]["teamSize"], 1);
        assert_eq!(
            lobby["customGameLobby"]["configuration"]["spectatorPolicy"],
            "NotAllowed"
        );
        assert_eq!(
            lobby["customGameLobby"]["configuration"]["hidePublicly"],
            true
        );
    }

    #[test]
    fn detects_a_local_first_blood_against_a_bot() {
        let events = json!({"Events":[
            {"EventName":"GameStart"},
            {"EventName":"ChampionKill","KillerName":"Draven"}
        ]});
        let players = json!([
            {"riotId":"Claude Code#JAVA","championName":"Draven","scores":{"kills":1}},
            {"summonerName":"Garen Bot","championName":"Garen","scores":{"kills":0}}
        ]);
        assert_eq!(
            find_bot_winner(&events, "Claude Code#JAVA", Some(&players)),
            Ok(Some(("FIRST_BLOOD", true)))
        );
    }

    #[test]
    fn detects_the_bot_first_blood_from_the_local_scoreboard() {
        let events = json!({"Events":[
            {"EventName":"ChampionKill","KillerName":"Garen"},
            {"EventName":"ChampionKill","KillerName":"Draven"}
        ]});
        let players = json!([
            {"riotId":"Claude Code#JAVA","championName":"Draven","scores":{"kills":1}},
            {"summonerName":"Garen Bot","championName":"Garen","scores":{"kills":1}}
        ]);
        assert_eq!(
            find_bot_winner(&events, "Claude Code#JAVA", Some(&players)),
            Ok(Some(("FIRST_BLOOD", false)))
        );
    }

    #[test]
    fn rejects_an_invitation_from_the_only_but_wrong_player() {
        let invites = json!([{
            "invitationId":"wrong-invite",
            "fromSummonerId":999
        }]);
        assert_eq!(matching_invitation_id(&invites, "123"), None);
        assert_eq!(
            matching_invitation_id(&invites, "999"),
            Some("wrong-invite".into())
        );
    }

    #[test]
    fn rejects_a_wrong_lobby_member() {
        let lobby = json!({"members":[
            {"gameName":"Claude Code","tagLine":"JAVA"},
            {"gameName":"Intrus","tagLine":"BAD"}
        ]});
        assert!(
            lobby_contains_exact_duel(&lobby, "Claude Code#JAVA", "Codex#GPT", "1001", "1002")
                .is_err()
        );
    }

    #[test]
    fn publishes_in_game_only_once() {
        let mut reported = false;
        assert!(mark_once(&mut reported));
        assert!(!mark_once(&mut reported));
        assert!(!mark_once(&mut reported));
    }

    #[test]
    fn refuses_non_loopback_listener_addresses() {
        assert!(validate_watcher_address("127.0.0.1:43991").is_ok());
        assert!(validate_watcher_address("[::1]:43991").is_ok());
        assert!(validate_watcher_address("0.0.0.0:43991").is_err());
        assert!(validate_watcher_address("192.168.1.10:43991").is_err());
    }

    #[test]
    fn production_origins_are_exact_and_never_include_development_defaults() {
        assert_eq!(
            web_origins("production", "https://pinkward.example").unwrap(),
            vec!["https://pinkward.example"]
        );
        assert!(web_origins("production", "").is_err());
        assert!(web_origins("production", "http://localhost:3000").is_err());
    }

    #[test]
    fn production_requires_a_unique_installation_credential() {
        assert!(
            validate_watcher_credential(
                true,
                "pinkward-watcher-installation-desktop01",
                "installation-secret-with-at-least-32-characters"
            )
            .is_ok()
        );
        assert!(
            validate_watcher_credential(
                true,
                "pinkward-watcher",
                "shared-secret-with-at-least-32-characters"
            )
            .is_err()
        );
        assert!(
            validate_watcher_credential(
                true,
                "pinkward-watcher-installation-desktop01",
                "too-short"
            )
            .is_err()
        );
    }

    #[test]
    fn detects_a_bot_reaching_one_hundred_cs_first() {
        let before = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":99}},
            {"summonerName":"Garen Bot","scores":{"creepScore":99}}
        ]);
        let after = json!([
            {"riotId":"Claude Code#JAVA","scores":{"creepScore":99}},
            {"summonerName":"Garen Bot","scores":{"creepScore":100}}
        ]);
        let mut tracker = BotCsTracker::default();
        assert_eq!(tracker.observe(&before, "Claude Code#JAVA"), Ok(None));
        assert_eq!(tracker.observe(&after, "Claude Code#JAVA"), Ok(Some(false)));
    }
}
