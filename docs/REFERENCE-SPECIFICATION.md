# Pinkward — Spécification consolidée d’infrastructure

> Copie de référence importée depuis la V1. Les constats « état observable »
> décrivent le dépôt lol-champions au 2026-08-26 ; l’implémentation propre de
> Showdown V2 est décrite dans docs/ARCHITECTURE.md et prime pour l’état courant.

Statut : référence normative consolidée
Date de consolidation : 2026-08-26 — état V2 actualisé le 2026-08-27
Sources fusionnées : docs/Infra.md et Prompt complet — Infrastructure Pinkward.md
Référence de l’existant : dépôt lol-champions à la date ci-dessus

## 1. Objet et règles de décision

Ce document remplace les deux sources comme spécification de référence pour les
travaux futurs d’infrastructure, de sécurité et d’exploitation de Pinkward. Il
distingue volontairement :

- l’état réellement implémenté, qui ne doit pas être cassé ;
- la cible architecturale, qui guide les extractions et nouveaux composants ;
- les exigences immédiatement applicables, même tant que le backend reste un
  monolithe modulaire ;
- les exigences différées, qui n’ont de sens qu’après l’apparition de plusieurs
  services déployables.

En cas de conflit, l’ordre de décision est :

1. comportement et contraintes observables dans le dépôt ;
2. docs/Infra.md pour l’infrastructure, le réseau et la sécurité ;
3. le prompt détaillé pour les précisions fonctionnelles et opérationnelles ;
4. les bonnes pratiques documentées ici quand les sources sont silencieuses.

Les mots « doit », « interdit » et « jamais » sont normatifs. « Devrait » indique
une exigence à appliquer sauf justification documentée. « Cible » ne signifie
pas que le composant existe déjà.

## 2. État observable au 2026-08-26

### 2.1 Composants présents

| Élément | État observé |
|---|---|
| Backend | Monolithe modulaire Spring Boot 4.1.0, Java 21, 276 fichiers Java |
| Modules métier | auth, player, party, queue, matchmaking, readycheck, teams, match, lobby, league, chat, presence, stats, admin, portal, websocket |
| Clients | application desktop Electron/React/TypeScript et portail web |
| Bot | bot Discord TypeScript en cours d’ajout dans le worktree |
| Persistance | une base PostgreSQL w3clol, un compte applicatif, Flyway V1 à V10 |
| Éphémère | Redis avec AOF, coordination, ready checks et relais WebSocket |
| Sécurité | Spring Security Resource Server, JWT HS256 interne, issuer et audience validés, cookie web avec CSRF double-submit |
| Temps réel | WebSocket natif et STOMP, authentification au handshake, relais Redis multi-instance |
| Matchmaking | verrou global court, verrous pessimistes par joueur et état READY_CHECK durable |
| Conteneurs | PostgreSQL, Redis et backend ; images versionnées ; backend non-root |
| Production | backend publié uniquement sur 127.0.0.1 ; Caddy est mentionné comme configuration externe au dépôt |
| CI/CD | GitHub Actions pour backend, desktop, web, image Docker, scripts, déploiement et releases signées |
| Tests | tests unitaires, Testcontainers PostgreSQL/Redis et simulation de parcours complet |

### 2.2 Composants absents ou partiels

- Aucun module Spring Cloud Gateway.
- Aucun service d’autorisation OAuth2, Riot RSO, flux Authorization Code + PKCE,
  JWKS, rotation de clés ou Client Credentials.
- Aucun déploiement séparé identity/player/matchmaking/match/community.
- Aucun RabbitMQ, exchange, queue, DLQ, consumer, outbox ou compte broker.
- Aucune séparation PostgreSQL par domaine ou par compte SQL.
- Aucune configuration Caddy versionnée et aucune configuration Cloudflare
  vérifiable depuis le dépôt.
- Aucune pile versionnée Grafana/Loki/OpenTelemetry ; Prometheus est seulement
  exposé par Actuator.
- Pas de script générique de restauration validée ni de smoke test de sécurité
  couvrant le futur edge/gateway.

### 2.3 Risques immédiats observés

- Le login de test fermé accepte une identité League déclarée par le client ; il
  ne constitue pas une preuve Riot RSO et ne convient pas à une ouverture
  publique.
- Les JWT restent symétriques et valables 12 heures par défaut. Le premier lot
  ajoute audience, nbf, jti, scopes et rôle USER, mais les scopes restent
  génériques tant que la matrice d’autorisation par route n’est pas achevée.
- Les patterns CORS de production contiennent des origines de développement et
  l’origine opaque file:// ; les profils local et production ne sont pas
  suffisamment séparés.
- Actuator expose metrics en plus de health/info/prometheus ; l’accès repose sur
  le binding loopback et le proxy, non sur une chaîne d’administration dédiée.
- PostgreSQL et Redis ont des valeurs locales simples par défaut. Elles restent
  acceptables uniquement pour un environnement local explicitement isolé.

## 3. Architecture de référence et trajectoire

### 3.1 Phase actuelle : monolithe modulaire

Le déploiement actuel reste supporté pendant la migration :

~~~text
Clients
  -> edge TLS/Caddy externe
  -> pinkward-backend
       -> PostgreSQL
       -> Redis
~~~

Les frontières de modules sont déjà les frontières d’extraction. Aucune
dépendance nouvelle ne doit contourner ces modules, partager une entité JPA
comme contrat ou introduire une lecture directe du futur stockage d’un autre
domaine.

### 3.2 Cible : services distribués

~~~mermaid
flowchart TD
    U[Application web] --> CF[Cloudflare]
    CF --> C[Caddy]
    C --> G[API Gateway]
    G --> I[Identity]
    G --> P[Player & Party]
    G --> MM[Matchmaking]
    G --> M[Match & Lobby]
    G --> CO[Community]
    MM --> R[RabbitMQ]
    R --> M
    M --> R
    R --> CO
    I --> PG[(PostgreSQL isolé)]
    P --> PG
    MM --> PG
    M --> PG
    CO --> PG
    G --> RE[(Redis)]
    MM --> RE
    CO --> RE
~~~

Services cibles :

| Service | Responsabilités |
|---|---|
| Identity | Riot RSO, comptes, sessions, JWT, refresh tokens, rôles, scopes et identités techniques |
| Player & Party | profils, Riot ID, groupes, invitations et préférences |
| Matchmaking | files 1v1/5v5, MMR, rôles, ready check, équilibrage et réservations |
| Match & Lobby | matchs, équipes, lobbies Riot, résultats et historique |
| Community | salons, messages, présence, notifications, modération et WebSocket/STOMP |

L’extraction ne commence qu’avec un contrat métier, des migrations, une
stratégie de données et des tests de compatibilité. Il est interdit de créer un
service vide uniquement pour reproduire le diagramme.

### 3.3 Conditions d’introduction du Gateway et de RabbitMQ

Spring Cloud Gateway devient un composant séparé lorsqu’au moins deux backends
déployables doivent être routés ou qu’une politique edge commune ne peut plus
être portée proprement par le backend. RabbitMQ est introduit avec la première
extraction nécessitant une livraison asynchrone interprocessus. Avant cela, les
événements Spring internes et Redis ne doivent pas être présentés comme des
garanties RabbitMQ.

Kubernetes, Istio, Consul, Eureka, Vault, Kafka, Elasticsearch, Keycloak et un
service mesh sont hors périmètre tant qu’un besoin mesuré ne les justifie pas.
Sous Compose, le DNS Docker assure la découverte.

## 4. Périmètre réseau

### 4.1 Flux autorisés

En cible :

~~~text
Internet -> Cloudflare -> Caddy -> API Gateway -> services
API Gateway -> Redis pour le rate limiting
Services -> leur stockage PostgreSQL
Services -> Redis seulement pour les usages éphémères autorisés
Services -> RabbitMQ selon des permissions minimales
Observabilité -> endpoints internes dédiés
~~~

Les réseaux Compose cibles sont edge, gateway, backend, data, messaging et
observability. Chaque conteneur ne rejoint que les réseaux nécessaires. Un
réseau unique pour tous les composants est interdit.

En production, seuls 80/443 peuvent être exposés publiquement. PostgreSQL,
Redis, RabbitMQ, leurs interfaces d’administration, le Gateway et les services
métier restent privés. Un binding 127.0.0.1 pour un reverse proxy local est
admis comme étape de transition.

### 4.2 Cloudflare

Domaines cibles :

- gyms.lol pour le web ;
- companion.pinkward.lol pour la démo/companion ;
- api.gyms.lol pour l’API ;
- leurs équivalents sous staging.pinkward.lol.

La production doit utiliser DNS proxy, SSL/TLS Full Strict, WAF, DDoS et bot
protection, rate limiting, cache statique et absence de cache sur les API
authentifiées. Les WebSockets doivent être activés. Les endpoints
d’authentification reçoivent les règles les plus strictes.

L’origin doit être protégée contre le contournement de Cloudflare par certificat
origin et, lorsque possible, filtrage firewall des plages Cloudflare. Seuls les
proxies explicitement approuvés peuvent influencer X-Forwarded-For. Cloudflare
ne remplace ni Spring Security, ni le firewall, ni les contrôles métier.

Le domaine public cible du web est gyms.lol. Le passage de l’API
à api.gyms.lol exige une migration coordonnée des clients, cookies, CORS,
WebSocket, CI/CD et DNS ; il ne doit pas être effectué isolément.

### 4.3 Caddy

Caddy est l’unique reverse proxy public de l’origin. Sa configuration versionnée
doit fournir TLS, redirection HTTPS, HSTS, en-têtes de sécurité, suppression des
en-têtes inutiles, support WebSocket, logs structurés et timeouts. Il transmet
l’API au Gateway cible ou, pendant la transition, au backend loopback.

Il ne doit pas logger Authorization, Cookie, Set-Cookie ou des paramètres
contenant un token. Les interfaces Grafana/RabbitMQ restent privées ou
accessibles par tunnel/VPN avec authentification.

### 4.4 API Gateway

Le Gateway cible utilise Spring Cloud Gateway, Spring Security Resource Server
et Redis. Il doit :

- vérifier signature, iss, aud, exp et nbf ;
- convertir les scopes en autorités ;
- appliquer un CORS par environnement et un rate limit par route ;
- router REST et WebSocket ;
- générer ou propager le correlation ID ;
- filtrer les en-têtes externes et internes ;
- refuser toute route non déclarée ;
- ne jamais journaliser les tokens.

Le Gateway est une première barrière, jamais la seule. Chaque service
revalide le token et réapplique ses autorisations.

## 5. Identité, OAuth2 et JWT

### 5.1 Modèle cible

Identity doit fournir :

- Riot RSO côté backend ;
- Authorization Code + PKCE pour le client web public ;
- aucun client_secret permanent dans le navigateur ;
- Client Credentials distinct par service ;
- access tokens courts et refresh tokens rotatifs ;
- JWKS, kid et rotation de clés ;
- signature asymétrique RS256 ou ES256 en cible.

Les credentials Riot, clés privées et secrets clients restent hors Git. Le
navigateur ne reçoit aucun credential LCU ; cette intégration nécessiterait un
compagnon local séparé et explicitement autorisé, hors du client web.

Client public cible : pinkward-web.

### 5.2 Contrat JWT utilisateur

Claims minimaux :

~~~json
{
  "iss": "https://identity.pinkward.lol",
  "sub": "player-uuid",
  "aud": ["pinkward-api"],
  "scope": "profile:read party:manage queue:write community:write",
  "roles": ["USER"],
  "iat": 0,
  "nbf": 0,
  "exp": 0,
  "jti": "uuid"
}
~~~

Les access tokens doivent être courts. La durée finale reste à calibrer ; 5 à
15 minutes est la cible initiale, avec refresh token pour éviter de dégrader
l’expérience. Tant que le refresh n’existe pas, la réduction de la durée de
12 heures doit être planifiée avec les clients.

Si des refresh tokens sont persistés, stocker leur hash, les faire tourner,
détecter la réutilisation, gérer expiration/révocation et auditer les sessions.

### 5.3 Tokens interservices

Chaque service possède son client_id, ses credentials, son audience et ses
scopes minimaux. Exemples :

- service:party:read ;
- service:matchmaking:write ;
- service:match:create ;
- service:notification:send.

Un compte technique partagé est interdit. Les appels synchrones emploient un
token court Client Credentials, des timeouts et des retries bornés seulement
pour les opérations sûres ou idempotentes.

### 5.4 Autorisation applicative

Politique par défaut : toutes les routes sont authentifiées, sauf routes
publiques explicitement inventoriées. Les services appliquent les scopes avec
Spring Security et les règles métier dans le service de domaine.

L’identité provient toujours du contexte Spring Security. Un userId, playerId,
partyId ou matchId envoyé par le client n’autorise jamais à lui seul une action.
Les contrôles incluent propriétaire/membre du groupe, membre du match, accès au
salon et propriété de l’opération.

Les entrées utilisent Jakarta Validation. Les erreurs de production ont un
format stable avec timestamp UTC, status, code, message non sensible et
correlationId. Elles n’exposent ni stack trace, SQL, hostname interne ni secret.

### 5.5 CORS et CSRF

Production autorise uniquement les origins réellement déployées. Les origins
localhost et 127.0.0.1 appartiennent au profil web local et ne doivent pas
rester dans la politique web de production sans justification. Les origins
tauri.localhost, null et file:// ne font plus partie de la cible.

Le cookie pinkward_session exige Secure, HttpOnly, SameSite adapté et CSRF.
L’API Authorization: Bearer pure reste stateless et n’exige pas de CSRF. Le
modèle double-submit actuellement implémenté est conservé jusqu’à la migration
OAuth2.

## 6. Données

### 6.1 PostgreSQL

PostgreSQL reste la source de vérité durable. La phase actuelle conserve la
base w3clol pour éviter une migration destructive. La cible est :

| Base | Compte |
|---|---|
| pinkward_identity | identity_service |
| pinkward_players | player_service |
| pinkward_matchmaking | matchmaking_service |
| pinkward_matches | match_service |
| pinkward_community | community_service |

Chaque compte n’accède qu’à son domaine. Une lecture directe des tables d’un
autre service est interdite ; utiliser API ou événement. Les extractions
doivent prévoir migration, double-écriture temporaire contrôlée ou réplication
événementielle, vérification puis retrait de l’ancien accès.

Flyway est conservé. En production, Hibernate reste en ddl-auto=validate.
Timestamps UTC, contraintes, index, transactions courtes, HikariCP dimensionné
sur la limite globale, connection/query timeouts et healthchecks sont requis.

### 6.2 Redis

Usages autorisés : rate limiting, présence, cache, verrous courts, sessions
temporaires, ready checks et relais temps réel. Les clés sont préfixées par
service et ont un TTL explicite quand elles sont temporaires, par exemple :

- pinkward:gateway:ratelimit:* ;
- pinkward:presence:* ;
- pinkward:matchmaking:reservation:* ;
- pinkward:party:invite:* ;
- pinkward:readycheck:*.

Redis n’est pas la source de vérité des matchs. Un verrou Redis a un owner, un
TTL et une libération atomique. Il ne remplace pas une transaction PostgreSQL
critique. La persistance RDB/AOF/aucune doit être choisie par usage ; l’AOF
actuel est une mesure de transition, pas une promesse de durabilité métier.
Authentification, ACL si utile, mémoire maximale et politique d’éviction sont
requises en production.

### 6.3 RabbitMQ

À son introduction, RabbitMQ utilise TLS, un vhost Pinkward, un compte par
service, des permissions publish/consume minimales, publisher confirms,
acknowledgements explicites, prefetch borné, queues durables et messages
persistants lorsque nécessaires.

Convention de routing keys :

- pinkward.matchmaking.match-found.v1 ;
- pinkward.match.created.v1 ;
- pinkward.match.cancelled.v1 ;
- pinkward.match.completed.v1 ;
- pinkward.match.lobby-ready.v1 ;
- pinkward.match.lobby-failed.v1 ;
- pinkward.party.created.v1 ;
- pinkward.party.updated.v1 ;
- pinkward.community.notification-requested.v1.

Enveloppe :

~~~json
{
  "eventId": "uuid",
  "eventType": "MATCH_FOUND",
  "eventVersion": 1,
  "occurredAt": "2026-08-26T17:00:00Z",
  "producer": "matchmaking-service",
  "correlationId": "uuid",
  "causationId": "uuid",
  "payload": {}
}
~~~

RabbitMQ fournit une livraison at-least-once, jamais exactly-once. Les
consommateurs détectent les doublons par eventId ou clé métier et gèrent crash
avant ACK, ordre différent, poison messages et replay. Chaque flux critique a
une DLQ, un nombre de retries, un délai, une procédure de replay et une alerte.
Les retries infinis sont interdits.

L’authentification broker, TLS, réseau privé et permissions précises satisfont
par défaut l’exigence d’événements « authentifiés ». Une signature applicative
n’est ajoutée que si le threat model la justifie.

### 6.4 Transactional outbox

Toute modification PostgreSQL devant impérativement publier un événement
interservice est enregistrée avec l’événement dans la même transaction. Un
publisher séparé livre l’outbox et marque la publication de façon idempotente.
Un simple save suivi de rabbitTemplate.convertAndSend n’est pas une garantie
acceptable.

## 7. Pipeline de matchmaking

~~~mermaid
sequenceDiagram
    participant U as Joueur
    participant G as Gateway
    participant MM as Matchmaking
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant M as Match & Lobby
    U->>G: join queue + région + rôles + JWT + Idempotency-Key
    G->>G: JWT, scope queue:write, rate limit
    G->>MM: requête authentifiée
    MM->>MM: joueur, région, rôles distincts, ownership
    MM->>DB: réservation atomique
    DB-->>MM: joueurs réservés
    MM->>MQ: MATCH_FOUND
    MQ->>M: MATCH_FOUND
    M->>M: équipes et rôles uniques + ready check
    U->>G: accepter ou refuser
    G->>M: commande liée au joueur JWT
    M->>MQ: MATCH_CONFIRMED ou MATCH_CANCELLED
    MQ->>MM: libération ou remise en file
    U->>G: lecture du lobby web confirmé
    G->>M: équipes et rôles attribués
~~~

États actuellement implémentés :

QUEUED et RESERVED dans la file ; READY_CHECK, CONFIRMED, CANCELLED, EXPIRED
et COMPLETED dans le service Match. Les transitions sont validées côté backend.
LOBBY_CREATING, LOBBY_READY, IN_GAME et FAILED restent des états cibles pour
l’intégration future au jeu et ne sont pas simulés par le navigateur.

La commande d’entrée en file transporte une région, un rôle principal et un
rôle secondaire distincts parmi TOP, JUNGLE, MID, BOT et SUPPORT. À la création
du match, le serveur attribue exactement une fois chacun des cinq rôles dans
chaque équipe. Pendant les 30 premières secondes, une réservation n’est créée
que si deux joueurs sont disponibles pour chaque rôle principal, soit un par
équipe. Après ce délai configurable, la recherche s’élargit aux rôles
secondaires et, si nécessaire, au remplissage. L’attribution maximise de façon
lexicographique le nombre de choix principaux : aucun nombre de choix
secondaires ne peut sacrifier un choix principal. En cas de conflit équivalent,
le joueur entré le plus tôt dans la file est prioritaire. Le client affiche le
résultat mais ne peut pas le modifier.

La cible distribuée réserve avec SELECT ... FOR UPDATE SKIP LOCKED, plusieurs
workers, transactions courtes et expiration des réservations. L’existant
utilise un verrou d’exécution Redis global et des verrous pessimistes par
joueur ; il évite aujourd’hui le double matchmaking mais ne remplit pas encore
le modèle multi-worker SKIP LOCKED.

Join/leave queue, ready check, create match et consumers MATCH_FOUND,
MATCH_CONFIRMED et MATCH_CANCELLED sont idempotents. Les commandes client
utilisent une Idempotency-Key ou une clé métier. Le ready check lie matchId,
playerId issu du JWT, deadline, state et version ; un joueur ne répond jamais
pour un autre.

Après confirmation des dix joueurs, le match CONFIRMED devient le lobby web
persistant du joueur. L’API ne retourne que le lobby auquel appartient le sujet
du JWT. Le serveur génère un nom de lobby et un mot de passe manuel aléatoire ;
le mot de passe est chiffré au repos avec AES-GCM et n’est renvoyé qu’aux
participants d’un lobby CONFIRMED. L’action locale de résultat demande victoire
ou défaite, persiste l’équipe gagnante et effectue la transition CONFIRMED vers
COMPLETED. Dans la même transaction, TrueSkill traite le résultat comme deux
équipes de cinq sans match nul. Chaque joueur commence avec `μ = 25` et
`σ = 25/3` ; `β = 25/6` et `τ = 25/300`. Les bots contribuent à la distribution
de performance de leur équipe avec la distribution initiale, mais seuls les
joueurs réels sont persistés. Le MMR public projette l’exposition prudente
`μ - 3σ` sur l’échelle existante initialisée à 1200. Un ledger unique par match
et joueur conserve les distributions avant/après. Une nouvelle soumission identique est idempotente ; une contradiction
après clôture est refusée. L’historique personnel retourne le résultat, le rôle,
l’équipe et la variation MMR. Les statistiques agrègent victoires, défaites,
win rate, pic MMR, forme récente et parties par rôle. Le KDA attend une
télémétrie autoritaire du futur companion Tauri 2 ou de Riot.

Le mode classé `ONE_V_ONE` possède une cote indépendante Glicko-2 et ne touche
jamais au TrueSkill 5v5. La distribution initiale est `rating = 1500`,
`RD = 350`, volatilité `0.06`, avec `tau = 0.5`. Chaque duel terminé constitue
une période de classement immédiate ; les deux nouvelles distributions sont
calculées depuis les deux états antérieurs au match. Un duel classé normal contient
exactement deux humains, un par équipe. Pour les tests locaux, un bot peut remplacer
le second joueur : il est évalué comme un adversaire Glicko-2 fixe à 1500 et seule
la cote du joueur humain est persistée, afin de ne pas polluer le classement avec
des comptes bots. Le ledger duel est unique par match et joueur. La cote reste provisoire tant que les placements ne sont pas
terminés ou que `RD > 160`. Les statistiques et le leaderboard régionaux 1v1
exposent MMR, RD, volatilité, progression, bilan et win rate. Le rollover de
saison archive l’état puis rapproche la cote de 1500 et augmente l’incertitude.
Les événements de projection utilisés pour trouver les matchs 5v5 ne sont pas
émis par ce flux 1v1.

Dans le profil local uniquement,
un joueur EUW resté seul cinq secondes reçoit neuf bots qui acceptent
automatiquement ; ce mécanisme est interdit en production. La simulation de
charge de 1 000 bots EUW reste un outil isolé dont les données sont nettoyées
après exécution.

## 8. Web, Riot et intégrations locales

Le navigateur est un client public non fiable. Il ne contient jamais mot de
passe DB, credentials Redis/RabbitMQ, clé privée JWT, secret OAuth confidentiel
ou secret Riot. Il appelle uniquement l’API publique avec Authorization Code +
PKCE et conserve ses tokens dans la session de l’onglet.

Les secrets Riot RSO restent côté backend. Les tokens Riot ne sont pas loggés.
Une application web ne lit pas directement le lockfile ou le
riotclient-auth-token. L’intégration LCU passera plus tard par un companion
Tauri 2 local distinct, à permissions minimales. La phase web actuelle génère
des coordonnées de lobby manuel réservées aux participants, mais elle ne crée
pas la partie dans le client Riot : un joueur doit encore créer la partie
personnalisée avec ces coordonnées. L’automatisation attend le companion.

RSO serveur et LCU local sont deux frontières d’authentification distinctes.

## 9. Community et WebSocket

La phase actuelle expose deux canaux WebSocket natifs ciblés, Party et Match.
Le polling navigateur toutes les deux secondes est supprimé. Invitations,
états prêts, recherche commune, match trouvé, confirmation, expiration et
résultat déclenchent une invalidation immédiate du snapshot REST concerné.

Le navigateur transmet le JWT comme sous-protocole `bearer.<JWT>` et jamais
dans l’URL. Player ou Match valide signature, issuer, audience, expiration,
scope et subject avant d’accepter la session. Les notifications sont envoyées
après commit et uniquement aux membres, invités ou participants concernés.
Taille de buffer et délai d’envoi sont limités ; la reconnexion recharge les
snapshots REST autoritaires.

Ce registre en mémoire convient au profil local mono-réplique. Un relais Redis
Pub/Sub ou RabbitMQ ciblé est requis avant la réplication horizontale. Le futur
Community Service portera salons, messages, modération et éventuellement STOMP.

La modération sépare permissions utilisateur et modérateur/admin et couvre
spam, abus, sanctions et rate limiting.

## 10. Conteneurs et environnements

### 10.1 Images

Les Dockerfiles Spring utilisent build multi-stage et runtime Java 21 minimal,
utilisateur non-root et images versionnées. En production, éviter latest comme
référence de base ; une stratégie de digest et SBOM est une amélioration
progressive.

Lorsque compatible : read_only, tmpfs, cap_drop ALL,
no-new-privileges, limites CPU/mémoire/pids et arrêt gracieux. Ne jamais monter
/var/run/docker.sock dans un service métier.

### 10.2 Compose

La structure future peut évoluer vers infra/compose, infra/caddy,
infra/postgres, infra/redis, infra/rabbitmq, infra/observability et
infra/scripts, mais les compose.yml racine existants restent la source de
lancement jusqu’à migration documentée.

Le Compose local peut publier PostgreSQL/Redis sur localhost pour le
développement. Le Compose de production ne publie aucune dépendance de données
et ne publie le backend qu’en loopback durant la phase transitoire.

Tous les composants critiques ont un healthcheck : readiness/liveness Spring,
pg_isready, redis-cli ping et rabbitmq-diagnostics ping. depends_on ne remplace
pas une reprise applicative.

### 10.3 Profils

- Local : Compose, logs lisibles, ports loopback et origines locales.
- Staging : DNS, données, secrets, DB et clients OAuth séparés.
- Production : Cloudflare, TLS, secrets protégés, monitoring, alerting,
  sauvegardes et accès administratif restreint.

Les fichiers Spring devraient être application.yml, application-local.yml,
application-staging.yml et application-prod.yml avec variables
d’environnement, sans duplication. Spring Cloud Config n’est pas introduit sans
besoin opérationnel.

## 11. Résilience et contrats

Les appels synchrones utilisent connect/read timeout, circuit breaker,
bulkhead si nécessaire et retries bornés. Une opération non idempotente n’est
jamais retryée sans clé de déduplication.

Les API utilisent /api/v1 tant que ce contrat reste compatible. Les événements
et DTO interservices sont versionnés. Les entités JPA ne sont pas des contrats
partagés.

Toutes les opérations utilisent UTC. Les identifiants distribués conservent les
UUID actuels ; aucune migration massive vers UUIDv7/ULID n’est justifiée.

Le graceful shutdown termine requêtes, consumers, transactions et WebSockets
autant que possible. Une réservation abandonnée et un verrou expirent et sont
récupérables après crash.

## 12. Secrets et données sensibles

Aucun secret réel dans Git. `.env.example` ne contient que des noms et
sentinelles de génération non exécutables. La production utilise secrets CI/CD,
Docker Secrets ou secret manager selon l’hébergeur.

Doivent pouvoir tourner sans rebuild :

- mots de passe PostgreSQL, Redis et RabbitMQ ;
- credentials Riot OAuth ;
- clés de signature JWT ;
- credentials OAuth2 interservices ;
- clé de chiffrement des mots de passe de lobby.

Ne jamais logger Authorization, Cookie, Set-Cookie, access_token,
refresh_token, password, client_secret, tokens Riot ou credentials LCU.
Minimiser les données persistées et documenter les données sensibles.

## 13. Observabilité

La cible utilise Micrometer, Prometheus, Grafana, OpenTelemetry et Loki. Les
logs structurés incluent timestamp, level, service, environment, traceId,
spanId, correlationId et, si pertinent, eventId.

Le correlation ID est propagé par Caddy, Gateway, REST, RabbitMQ et WebSocket.

Métriques techniques : requêtes/latence HTTP, 4xx/5xx, JVM, heap, GC, threads,
pool DB, Redis, RabbitMQ et WebSockets.

Métriques métier :

- pinkward_matchmaking_queue_size ;
- pinkward_matchmaking_wait_seconds ;
- pinkward_matchmaking_matches_found_total ;
- pinkward_matches_created_total ;
- pinkward_matches_failed_total ;
- pinkward_lobby_creation_seconds ;
- pinkward_lobby_creation_failed_total ;
- pinkward_ready_check_timeout_total ;
- pinkward_websocket_connections ;
- pinkward_websocket_messages_total.

userId, playerId, partyId, matchId et riotId sont interdits comme labels
Prometheus.

Dashboards minimaux : overview, Gateway, JVM, PostgreSQL, RabbitMQ, Redis,
Matchmaking et Community/WebSocket. Alertes : 5xx, latence, service down, DLQ,
backlog, saturation PostgreSQL, pression mémoire Redis, matchmaking lent et
échecs lobby. Tout seuil est documenté et ajusté sur des mesures.

Actuator public n’expose que ce qui est nécessaire. Les endpoints
administratifs restent sur un réseau interne ou sont authentifiés.

## 14. Sauvegarde et reprise

PostgreSQL utilise pg_dump compressé, rotation, chiffrement et stockage externe.
La procédure documente RPO, RTO, restauration et test périodique. Une sauvegarde
non restaurée en test n’est pas validée.

RabbitMQ utilise volumes, queues durables et messages persistants selon le
besoin, mais ne remplace jamais l’historique PostgreSQL. Redis suit la stratégie
de persistance choisie par usage. Les volumes critiques ne reposent pas sur le
filesystem éphémère du conteneur.

Les scripts start/stop/restart/health/backup/restore/smoke-test doivent être
simples et sûrs. Une suppression de volumes exige un flag explicite tel que
--force.

## 15. CI/CD et chaîne logicielle

Le pipeline existant GitHub Actions est conservé. Il doit progressivement
couvrir checkout, compilation, tests unitaires/intégration, analyse statique,
scan de dépendances, artefacts, images, scan conteneur, publication, déploiement,
smoke test et vérification de santé.

Les secrets proviennent de GitHub Environments/Secrets. Les connexions SSH
restent épinglées. Les déploiements conservent sauvegarde préalable,
healthcheck et rollback. Trivy, OWASP Dependency-Check et SBOM CycloneDX/SPDX
sont des améliorations planifiées ; aucun outil n’est ajouté sans maintien
opérationnel.

Les images applicatives de release doivent porter un tag immuable ou digest.
L’usage interne actuel d’un tag latest avec image de rollback est toléré
uniquement comme mécanisme transitoire du script existant.

## 16. Tests et critères d’acceptation

Tests minimaux adaptés aux composants présents :

- compilation, tests unitaires et Testcontainers ;
- migrations reproductibles ;
- Compose valide et services healthy ;
- absence de secret suivi par Git ;
- JWT absent/invalide/expiré/mauvais issuer/mauvaise audience refusé ;
- scope absent renvoie 403 sur une route scoped ;
- CSRF absent renvoie 403 avec authentification cookie ;
- WebSocket anonyme refusé ;
- contrôle horizontal des groupes, matchs et salons ;
- absence d’exposition publique PostgreSQL/Redis/RabbitMQ/services ;
- idempotence des opérations et consumers critiques ;
- reprise après Redis/RabbitMQ restart selon leur rôle ;
- DLQ et replay quand RabbitMQ existe ;
- sauvegarde et restauration testées.

Le smoke test cible vérifie Caddy, Gateway, Identity et les dépendances présentes,
puis une route inconnue refusée. Il ne doit pas prétendre tester un composant
absent.

## 17. Threat model minimal

| Menace | Protections de référence |
|---|---|
| JWT volé | durée courte, TLS, audience, scopes, rotation/révocation |
| Refresh token volé | hash, rotation, reuse detection, révocation |
| Client web compromis | PKCE, aucun secret embarqué, tokens courts, backend autoritaire |
| Contournement Cloudflare | firewall IP, certificat origin, proxy trust explicite |
| Brute force auth | rate limit, backoff, alerting, erreurs non énumérables |
| Spam matchmaking/chat | budget par utilisateur/IP/client, sanctions |
| Double matchmaking | transaction DB, SKIP LOCKED cible, idempotence |
| Replay RabbitMQ | eventId, inbox de déduplication, droits broker |
| Injection WebSocket | JWT, destinations contrôlées, validation et limites |
| Accès horizontal | identité du SecurityContext et ownership métier |
| Service compromis | moindre privilège DB/broker, scopes/audiences distincts |
| Fuite de secrets | hors Git, redaction logs, rotation |
| Compromission DB | comptes isolés, chiffrement backups, restauration testée |

Pour un VPS Linux : firewall UFW/nftables, SSH par clé, login root et mots de
passe SSH désactivés si possible, mises à jour et fail2ban si pertinent.

## 18. Plan d’implémentation

### Lot 0 — alignement sans rupture

1. Ajouter audience, nbf, jti, scopes et rôles aux JWT internes. **Implémenté.**
2. Valider l’audience en plus de l’issuer dans le Resource Server. **Implémenté.**
3. Séparer CORS local et production.
4. Ajouter tests de refus d’audience/issuer et inventaire des routes publiques.
5. Versionner un modèle Caddy transitoire et un smoke test du déploiement réel.

### Lot 1 — identité publique

1. Remplacer le login fermé non vérifié par Riot RSO approuvé.
2. Introduire Authorization Code + PKCE et refresh rotation.
3. Passer à RS256/ES256, JWKS, kid et rotation.
4. Définir les scopes par route et les permissions métier testées.

### Lot 2 — exploitation du monolithe

1. Durcir Compose production et authentifier Redis.
2. Ajouter Caddy versionné, protection origin et profils Spring.
3. Ajouter dashboards, alertes, traces et centralisation des logs.
4. Ajouter backup/restore/smoke tests et scans CI.

### Lot 3 — première extraction

1. Choisir un bounded context selon couplage et charge mesurés.
2. Définir son API, ses événements et sa migration de données.
3. Introduire Gateway, Client Credentials et identités DB.
4. Introduire RabbitMQ, DLQ, outbox/inbox et observabilité.

### Lot 4 — cible distribuée

Extraire progressivement Identity, Player & Party, Matchmaking, Match & Lobby et
Community. À chaque étape, conserver tests de contrat, rollback et aucune
lecture cross-database.

## 19. Matrice de conformité initiale

| Élément | État | Implémentation observée | Reste à faire |
|---|---|---|---|
| Cloudflare | Partiel/invérifiable | références web/CI | configuration et protection origin |
| Caddy | Partiel | configuration VPS hors dépôt | versionner et tester |
| API Gateway | Absent | backend direct loopback | introduire à la première extraction |
| Identity Service | Partiel | module auth interne | RSO, OAuth2, JWKS, extraction |
| Player & Party | Partiel | modules player/party | service et DB isolés |
| Matchmaking | Partiel | queue/matchmaking/readycheck | SKIP LOCKED, service isolé |
| Match & Lobby | Partiel | modules match/lobby/league | service, outbox, événements |
| Community | Partiel | chat/presence/websocket | service et broker relay |
| Resource Server | Partiel | Spring Security JWT | audience/scopes puis clés asymétriques |
| Client Credentials | Absent | aucun appel technique OAuth2 | clients distincts |
| JWT issuer/audience | OK pour la phase HS256 | issuer et audience validés | migrer vers JWKS asymétrique |
| PostgreSQL isolé | Absent | une base/un compte | extraction progressive |
| Redis | Partiel | AOF, coordination et relay | auth/ACL/TLS/profils |
| RabbitMQ | Absent | aucun composant | première extraction |
| DLQ | Absent | sans objet avant RabbitMQ | définir par flux |
| Outbox | Absent | événements Spring after-commit | première publication interservice |
| Matchmaking atomique | Partiel | verrou global + row locks | SKIP LOCKED multi-worker |
| WebSocket Security | OK local | JWT en sous-protocole, ciblage et buffers bornés | relais multi-instance et rate limit dédié |
| Observabilité | Partiel | Actuator/Prometheus/logs ECS | OTel, Loki, Grafana, alertes |
| Backups | Partiel | dump avant déploiement | rotation, externe, restore test |
| CI/CD | Partiel | CI, deploy, rollback, signature | scans, SBOM, smoke sécurité |

## 20. Registre de fusion et décisions

### Éléments communs

Les deux sources imposent la topologie Cloudflare/Caddy/Gateway/services, la
défense en profondeur Spring Security, issuer/audience/scopes, l’isolation
PostgreSQL, Redis éphémère, RabbitMQ sécurisé, WebSocket contrôlé et le pipeline
MATCH_FOUND vers LOBBY_READY. Le prompt détaillé reprend presque intégralement
Infra.md puis développe son exploitation.

### Informations propres à Infra.md

Infra.md apporte le diagramme canonique compact, les cinq bounded contexts, les
bases/comptes cibles et le pipeline sécurisé. Ces éléments restent la cible
architecturale.

### Informations propres au prompt détaillé

Le prompt ajoute les exigences de Compose/réseaux, Cloudflare/Caddy, PKCE,
refresh rotation, conventions RabbitMQ, DLQ, outbox, états, idempotence,
resilience, observabilité, sauvegardes, CI/CD, threat model, scripts, smoke
tests, ordre d’implémentation et matrice de conformité. Elles sont conservées
dans les sections thématiques au lieu d’être répétées.

### Contradictions corrigées

1. « Backend distribué en microservices » contre dépôt monolithique : le
   monolithe modulaire est la phase actuelle ; les microservices sont la cible.
2. « Créer Caddy/Gateway/RabbitMQ » contre absence de services réels : ils sont
   introduits par besoin et non simulés.
3. Domaines cibles gyms.lol et api.gyms.lol : la bascule doit rester coordonnée
   entre le frontend, le backend, OAuth, CORS, WebSocket et DNS.
4. PostgreSQL isolé par service contre base w3clol existante : aucune
   séparation destructive immédiate ; isolation lors des extractions.
5. SKIP LOCKED obligatoire contre verrouillage existant : le mécanisme actuel
   reste admis pour une instance logique ; SKIP LOCKED est requis pour les
   workers distribués.
6. CSRF parfois désactivable pour Bearer contre cookie web réel : CSRF reste
   obligatoire pour le cookie et non pour le header Bearer.
7. JWT asymétrique court contre HS256 12 h existant : migration en deux temps,
   d’abord claims/validation puis Authorization Server/JWKS/refresh.
8. Caddy à créer contre Caddy déjà opéré hors dépôt : versionner un modèle sans
   supposer que la configuration du VPS est identique.
9. Prometheus/Grafana/Loki/OTel annoncés comme composants contre seul
   Prometheus présent : matrice corrigée en Partiel.
10. RabbitMQ présenté comme communication existante contre événements Spring
    et Redis observés : RabbitMQ est différé à la première extraction.

### Redondances supprimées

Les interdictions sur secrets, exposition réseau, validation JWT, permissions
métier, idempotence et absence de sur-ingénierie apparaissaient plusieurs fois.
Elles sont chacune formulées une fois dans leur section puis référencées par les
critères d’acceptation.

### Ambiguïtés restantes

- disponibilité et configuration exactes de Cloudflare/Caddy en production ;
- calendrier d’ouverture publique et approbation Riot RSO ;
- premier bounded context à extraire ;
- budgets de rate limit et seuils d’alerte mesurés ;
- RPO/RTO et destination externe des backups ;
- choix RS256 ou ES256 et politique de rotation ;
- durée exacte des access/refresh tokens ;
- date de bascule coordonnée vers gyms.lol et api.gyms.lol ;
- calibration future du TrueSkill et du soft reset avec des volumes réels ;
- stratégie RabbitMQ/Redis/STOMP retenue pour le Community Service extrait.

Ces ambiguïtés ne bloquent pas le Lot 0.

## 21. Journal d’implémentation

### 2026-08-26 — début du Lot 0

- ajout de JWT_AUDIENCE, valeur par défaut pinkward-api ;
- émission des claims aud, nbf, jti, scope et roles ;
- validation combinée issuer, timestamps et audience dans le Resource Server ;
- test positif des claims et test de refus d’une mauvaise audience ;
- propagation de la configuration dans application.yml, application-test.yml,
  .env.example et compose.production.yml.

Cette évolution invalide volontairement les JWT émis avant le déploiement, car
ils ne possèdent pas le claim aud. Le déploiement doit annoncer une
réauthentification des sessions existantes. Ce coût ponctuel est préféré à une
fenêtre de compatibilité qui continuerait d’accepter des tokens sans audience.

### 2026-08-26 — décision web-first V2

- l’application utilisateur devient exclusivement web ; le client Electron
  et le client OAuth `pinkward-desktop` sortent de la cible ;
- ajout d’un client React/TypeScript servi par Caddy à la racine publique ;
- connexion `pinkward-web` par Authorization Code + PKCE et refresh rotation ;
- accès web aux commandes rejoindre, consulter et quitter la file de
  matchmaking ;
- toute intégration LCU future nécessite un compagnon local séparé : aucun
  credential LCU n’est accessible au navigateur.

### 2026-08-26 — pipeline Match et frontière companion

- le produit actif reste intégralement web et backend ; aucun accès LCU n’est
  implémenté dans le navigateur ou dans les services serveur ;
- un éventuel companion est reporté à une application Tauri 2 séparée, avec
  permissions minimales et contrat réseau dédié ;
- ajout du Match Service et de sa base PostgreSQL isolée ;
- consommation de MATCH_FOUND par une queue RabbitMQ durable avec retry et DLQ ;
- ajout d’une inbox eventId idempotente, de la persistance des deux équipes et
  des états READY_CHECK, CONFIRMED, CANCELLED et EXPIRED ;
- ajout des scopes match:read et match:ready, des routes Gateway sécurisées et
  de l’interface web accepter/refuser ;
- publication transactionnelle de MATCH_CANCELLED, avec inbox idempotente,
  retry et DLQ côté Matchmaking ;
- en cas de refus, le joueur refusant quitte la recherche et les neuf autres
  sont remis en file ; en cas d’expiration, seuls les joueurs ayant accepté
  sont remis en file afin d’éviter une boucle avec le même groupe incomplet ;
- ajout d’un remplissage de test EUW, local uniquement et désactivé par défaut :
  après cinq secondes, un groupe contenant au moins un joueur réel est complété
  jusqu’à dix avec des bots qui acceptent automatiquement ;
- ajout d’un script de charge isolé pour 1 000 bots EUW, avec mesure puis
  nettoyage automatique des matchs, événements et entrées synthétiques ;
- publication de MATCH_CONFIRMED lorsque le dixième joueur accepte, puis
  suppression idempotente des dix réservations côté Matchmaking ; un match
  confirmé reste dans l’historique mais sort immédiatement du ready-check
  courant afin que l’interface revienne à l’accueil ;
- le flux local complet est couvert par smoke-test.ps1 -IncludeMatchFlow.

### 2026-08-27 — profils joueurs web

- ajout d’un Player Service déployable et de la base `pinkward_players` isolée,
  sans accès direct aux bases Matchmaking ou Match ;
- ajout d’un profil créé à la demande depuis le sujet JWT : pseudo public
  unique sans tenir compte de la casse, région EUW/EUNE/NA, rôle principal et
  rôle secondaire distincts ;
- ajout de `GET/PUT /api/v2/players/me` et des commandes de présence sous
  `/api/v2/players/me/presence` ;
- protection en profondeur par audience JWT et scopes `profile:read` et
  `profile:write` au Gateway puis dans Player Service ;
- présence web par heartbeat toutes les vingt secondes, expiration serveur à
  quarante-cinq secondes et passage hors ligne explicite à la déconnexion ;
- ajout de l’écran Profil React, réutilisation des préférences enregistrées
  dans le formulaire de matchmaking et affichage du pseudo dans ready-check et
  lobby ;
- ajout de Flyway V1, tests unitaires du domaine et couverture du parcours
  profil dans le smoke test local.

### 2026-08-27 — groupes et recherche commune

- activation de l’onglet web « Équipe » avec progression création → invitation
  → prêts → recherche commune ;
- ajout des groupes persistants de deux à cinq joueurs, chef de groupe,
  invitations par pseudo, acceptation/refus, région commune et état prêt par
  membre dans la base Player isolée ;
- ajout d’un client technique distinct `pinkward-player` et du scope minimal
  `service:queue:party` pour l’appel direct Player vers Matchmaking ;
- insertion transactionnelle de toutes les entrées d’un groupe sous le même
  `party_id`, retrait commun et protection contre un membre déjà en file ;
- sélection Matchmaking par unités indivisibles et partition en deux équipes
  de cinq : un groupe n’est ni séparé entre deux matchs ni placé dans les deux
  équipes d’un même match ;
- ajout d’un mode de simulation strictement local : un pseudo inconnu devient
  un membre simulé, contrôlable par le chef et auto-acceptant au ready-check ;
- ajout du smoke test `-IncludePartyFlow`, qui valide cinq membres, tous prêts
  et cinq entrées atomiques avant nettoyage.

### 2026-08-27 — résultats, statistiques et MMR

- ajout de l’équipe gagnante et de l’horodatage de clôture au Match Service ;
- ajout d’un ledger MMR unique par match/joueur et de cotes persistantes avec
  verrou optimiste ; seuls les joueurs réels sont classés ;
- calcul TrueSkill transactionnel en 5v5 avec distribution initiale `(25, 25/3)`,
  résultat sans nul et bots non classés inclus dans la performance d’équipe ;
- ajout du scope `match:result`, des routes Gateway et des API personnelles
  `/history` et `/statistics` sous `match:read` ;
- activation de l’écran web Historique : MMR, rang, pic, bilan, win rate,
  forme récente, répartition par rôle et variation par match ;
- remplacement de la simple clôture locale par les actions Victoire/Défaite ;
  le KDA reste différé jusqu’à une source de télémétrie autoritaire ;
- extension du smoke test bots jusqu’au résultat, à l’historique et au MMR,
  avec restauration exacte de la cote et nettoyage des données synthétiques.

### 2026-08-28 — résultats autoritaires et session web

- retrait du scope public `match:result` et blocage en profondeur de la route
  manuelle dans le BFF, le Gateway et le Match Service ;
- suppression des boutons Victoire/Défaite : un résultat classé provient
  exclusivement du Watcher local autorisé ou d’un futur service de confiance ;
- révocation distante du refresh token à la déconnexion, propagation entre
  onglets et en-têtes CSP, anti-framing et anti-sniffing sur l’application web ;
- jeton de session local éphémère obligatoire pour les actions mutantes du
  Watcher, limité aux origines web locales explicitement autorisées.

### 2026-08-29 — attestation Riot et résultats bot durcis

- `/bot-assignment` et `/bot-result` sont invisibles depuis le BFF public et
  exigent le scope technique `service:match:bot-result` au Gateway ;
- le navigateur ne fournit plus l'équipe gagnante ni les identifiants du lobby :
  le Watcher envoie un booléen humain gagnant, un objectif typé et un horodatage
  récent, puis Match Service dérive l'équipe depuis son roster autoritaire ;
- la liaison Riot ID brute est remplacée par un challenge PostgreSQL à usage
  unique, expirant après 90 secondes, complété directement par le Watcher avec
  le scope `service:profile:link` ; le PUUID est stocké avec unicité ;
- l'identité et l'état du Watcher exigent aussi la session loopback éphémère ;
- le client backend du Watcher valide normalement TLS et interdit HTTP distant,
  tandis que l'acceptation du certificat Riot local est confinée aux deux
  surfaces `127.0.0.1` sans proxy ni redirection ;
- l'ingestion bot reste désactivée par défaut hors profil local et le secret
  OAuth2 technique est injecté au lancement, jamais compilé dans le binaire.

### 2026-08-27 — matchmaking classé par MMR

- publication transactionnelle de `PLAYER_RATING_UPDATED` après chaque
  résultat humain et consommation par queue RabbitMQ durable avec inbox et DLQ ;
- projection locale des cotes dans Matchmaking, sans lecture de la base Match
  et sans accepter de MMR fourni par le navigateur ;
- snapshot MMR persistant sur chaque entrée de file, cote initiale 1200 ;
- fenêtre initiale ±100, élargie de 50 toutes les 15 secondes jusqu’à ±600 ;
- bots locaux et membres simulés alignés sur la cote moyenne des humains qu’ils
  complètent afin de conserver la testabilité locale ;
- partition exhaustive des unités de groupe pour former deux équipes de cinq,
  avec minimisation de l’écart des totaux de compétence et groupe toujours indivisible ;
- maintien indépendant de la priorité des rôles et de son élargissement après
  attente ; affichage du MMR utilisé directement dans la file web.

### 2026-08-27 — passage de l’Elo à TrueSkill 5v5

- remplacement du calcul Elo individuel par une mise à jour TrueSkill unique
  sur les deux équipes de cinq ;
- persistance de `μ` et `σ` dans Match ainsi que dans le ledger avant/après ;
- maintien du MMR lisible comme projection prudente `μ - 3σ`, initialisée à
  1200 pour préserver l’échelle et les rangs existants ;
- version 2 de `PLAYER_RATING_UPDATED`, extension de la projection Matchmaking et du
  snapshot de file avec `μ` et `σ` ;
- compatibilité de recherche décidée sur la moyenne TrueSkill et partition des
  groupes minimisant l’écart des sommes de `μ` entre les deux équipes ;
- migrations V7 rétrocompatibles et smoke test étendu avec restauration des
  distributions TrueSkill après le scénario synthétique.

### 2026-08-27 — temps réel et classement saisonnier

- suppression du polling web de deux secondes au profit de canaux WebSocket
  natifs Party et Match avec reconnexion automatique ;
- authentification du handshake par JWT en sous-protocole, contrôle des scopes,
  sessions ciblées et notifications uniquement après commit ;
- notifications immédiates pour invitations, états prêts, recherche de groupe,
  match trouvé, confirmation, expiration et résultat ;
- ajout d’un leaderboard par région limité aux joueurs ayant terminé cinq
  placements, avec rang, win rate et progression de saison ;
- ajout des saisons S2026/S2027, archivage de fin de saison et soft reset à
  50 % de l’écart de moyenne TrueSkill avec remontée de l’incertitude ;
- ajout de la migration Match V8 et de l’écran web Classement.

### 2026-08-27 — historique avancé et socle d’exploitation renforcé

- remplacement de l’historique plat par une API paginée et filtrable par rôle,
  région et résultat ; ajout d’une fiche de match qui retourne les cinq
  coéquipiers, les cinq adversaires, leurs rôles et les variations MMR ;
- ajout dans React des filtres, de la pagination et du panneau de détail ; le
  KDA reste explicitement différé jusqu’à Riot ou au companion Tauri 2 ;
- persistance SQL des clients OAuth et persistance des clés RSA-3072 dans un
  volume séparé de l’image ; rotation automatique par `kid` avec période de
  recouvrement, sans invalidation des JWT lors d’un redémarrage ;
- authentification Redis, AOF `everysec`, mémoire bornée, politique d’éviction,
  mode protégé et suppression des commandes de flush ;
- vhost RabbitMQ dédié, administrateur séparé et un compte par service avec
  droits limités aux exchanges partagés et à son propre namespace de files ;
- rate limits externalisés et mesurés par un test de burst ; compteurs Gateway
  des requêtes, durées, rejets 429 et erreurs 5xx ;
- ajout de Prometheus, Alertmanager et exporters Redis/RabbitMQ/PostgreSQL,
  management ports internes et six règles d’alerte versionnées ;
- sauvegarde des quatre bases en archives compressées vérifiées, manifeste
  SHA-256, rétention et restauration explicite par base ;
- limites de production restantes documentées : secrets managés, copies
  chiffrées hors hôte, receiver d’astreinte, tests de restauration planifiés et
  TLS Redis/RabbitMQ dès franchissement d’une frontière de confiance.

### 2026-08-27 — classement Glicko-2 indépendant pour le 1v1

- ajout de Glicko-2 standard avec cote initiale 1500, RD 350, volatilité 0.06
  et `tau = 0.5`, vérifié sur l’exemple numérique de référence ;
- ajout des cotes, variations par match et archives saisonnières 1v1 dans la
  migration Match V9, sans migration ni écriture dans le ledger TrueSkill ;
- extension du contrat `MATCH_FOUND` à deux humains pour `ONE_V_ONE`, avec
  interdiction des bots et création d’une équipe bleue et d’une équipe rouge ;
- traitement idempotent du résultat duel, mise à jour symétrique gagnant/perdant
  depuis les états antérieurs et statut provisoire fondé sur placements + RD ;
- ajout des API `/duel/statistics` et `/duel/leaderboard`, de l’historique
  multi-mode et des cartes web Glicko-2 (MMR, RD, volatilité et progression) ;
- maintien volontaire de la recherche de match et de la projection RabbitMQ
  en 5v5 TrueSkill ; la création/rejoindre un lobby Riot et la télémétrie du
  duel seront branchées par un watcher Windows local avec une fenêtre de statut
  native légère et une icône dans la zone de notification.

### 2026-08-28 — duel direct et watcher local

- le 1v1 n'utilise pas le matchmaking : un joueur recherche le pseudo Showdown
  exact de son adversaire et envoie une invitation valable dix minutes ;
- le navigateur authentifié crée un challenge de liaison à usage unique, ouvre
  une session locale éphémère auprès du watcher, lit le PUUID, le Riot ID,
  l'icône et le niveau détectés par le LCU, puis complète son propre challenge ;
- cette liaison est qualifiée de « compte détecté localement » : sans Riot RSO,
  elle ne prouve pas cryptographiquement la propriété face à un binaire modifié ;
- après acceptation, Match Service crée un match `ONE_V_ONE`, une équipe par
  humain, ainsi qu'un nom et un mot de passe de lobby chiffré au repos ;
- chaque navigateur remet à son watcher loopback un jeton aléatoire de 256 bits,
  limité au joueur et au match, haché côté serveur, rotatif et valable deux heures ;
- le watcher hôte crée le lobby, invite l'adversaire, attend deux membres et lance
  la sélection ; le watcher invité tente le join direct puis accepte l'invitation ;
- avant toute mutation LCU, le watcher compare le PUUID et le Riot ID complets du
  compte ouvert avec l'affectation autoritaire renvoyée par Match Service ;
- le watcher utilise la Live Client Data API pour premier sang, première tour et
  premier joueur à 100 CS, sans lecture mémoire ni injection ;
- deux observations indépendantes doivent désigner le même objectif et le même
  vainqueur avant toute écriture du résultat et toute mise à jour Glicko-2 ;
- si un événement et un passage à 100 CS apparaissent pour la première fois dans
  le même cycle, aucun résultat n'est publié et le duel passe en revue manuelle ;
- le LCU est une interface locale non supportée officiellement : ses routes sont
  confinées à l'adaptateur Rust et devront être retestées après chaque patch Riot.

### 2026-08-29 — secrets locaux uniques et edge limité à loopback

- remplacement des valeurs de développement partagées par seize secrets
  Base64URL indépendants issus d’un CSPRNG, générés au premier démarrage ;
- protection ACL de `infra/.env`, exclusion Git/Docker des variantes `.env.*`
  et suppression du mot de passe RabbitMQ historique ;
- rotation coordonnée et réversible des rôles PostgreSQL, comptes RabbitMQ,
  mot de passe Redis, clé AES de lobby, comptes locaux et clients OAuth ;
- blocage de la rotation AES tant qu’un lobby confirmé utilise encore la clé,
  sauvegarde PostgreSQL préalable et absence de copie plaintext des anciens secrets ;
- publication de Caddy uniquement sur `127.0.0.1:8088`, avec contrôle automatique
  de tous les ports hôte et test de génération/entropie dans le script de vérification et la CI.

### 2026-08-29 — durcissement issu de l’audit de sécurité

- création d’un duel limitée à l’identifiant Showdown adverse ; les Riot IDs et
  la région vérifiés sont relus côté serveur via un client OAuth dédié ;
- double authentification des routes watcher par jeton OAuth technique et jeton
  brut limité au match/joueur, avec fenêtre de fraîcheur des observations ;
- séparation du scope watcher `service:duel:observe` et du scope de résultat de
  confiance, réservé au client serveur `pinkward-result-ingestor` ;
- minimisation de l’annuaire et des réponses de challenge : les Riot IDs adverses
  ne sont plus exposés au navigateur ;
- restriction des origines WebSocket aux valeurs exactes configurées et service
  de l’application construite derrière Caddy plutôt qu’une redirection de dev ;
- révocation OAuth du refresh token à la déconnexion et propagation inter-onglets ;
- permissions RabbitMQ par routing key et vérification du producteur des événements ;
- masquage des paramètres OAuth sensibles dans les journaux d’accès Caddy ;
- suppression du header WebSocket contenant le bearer token dans ces journaux ;
- CI étendue aux cinq images Java, au frontend, à l’audit npm et au watcher Rust.
