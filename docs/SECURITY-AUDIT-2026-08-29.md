# Audit de sécurité complet — Pinkward Showdown (seconde édition)

Date : 2026-08-29
Périmètre autorisé : `C:\Users\Alexis\Desktop\Showdown` et ses services locaux
Référentiels : OWASP ASVS 5.0.0, OWASP Top 10 2025, OWASP API Security Top 10 2023, OWASP WSTG 4.2
Nature : revue défensive locale du code, de la configuration, des dépendances, des tests et des données d'exploitation visibles ; corrections appliquées dans le dossier

## 1. Résumé exécutif

Cette seconde passe a repris l'inventaire et les frontières de confiance depuis
le code courant. Elle a découvert un contournement important que la première
passe n'avait pas isolé : le client watcher possédait encore le scope générique
`service:match:result`. Un attaquant ayant extrait son secret partagé pouvait
donc appeler directement la route de résultat et éviter le jeton éphémère ainsi
que le consensus des deux agents. Le scope générique appartient désormais à un
client serveur séparé, `pinkward-result-ingestor`, et le watcher ne possède plus
que `service:duel:observe` pour ce flux.

La seconde passe a également corrigé :

- la conservation d'une session dans les autres onglets après déconnexion ;
- la présence potentielle du JWT WebSocket dans les journaux Caddy via
  `Sec-WebSocket-Protocol` ;
- l'exposition inutile des Riot IDs dans l'annuaire et les invitations ;
- un bouton de consentement utilisant du JavaScript inline bloqué par la CSP ;
- les permissions POSIX du fichier contenant les clés privées JWT ;
- des références GitHub Actions mutables et un toolchain Rust non figé ;
- deux mises à jour de correctifs stables de BOM Java.

Verdict :

- **développement local : GO avec conditions**. Toutes les validations locales
  disponibles passent après correction ;
- **exposition Internet/production : NO-GO**. L'identité de production, la
  protection d'un secret watcher distribué, la SCA Java, le scan et la signature
  des images, TLS/Cloudflare en situation réelle et les sauvegardes chiffrées
  restent des prérequis bloquants.

Aucun secret n'est reproduit dans ce rapport.

## 2. Périmètre, inventaire et écarts avec `Infra.md`

### Architecture réellement observée

```text
Navigateur
   |
   v
Caddy :8088
   |-- pages OAuth/OIDC ----------> Identity Service :9000
   |-- /api/** et WebSocket ------> API Gateway :8080
   `-- application construite ----> Web App :80

API Gateway
   |-- Player Service
   |-- Matchmaking Service
   `-- Match Service

Services -> PostgreSQL séparés / Redis authentifié / RabbitMQ vhost Pinkward
Watcher Rust local -> LCU/Live Client loopback + API Pinkward
Prometheus / Alertmanager / exporteurs sur réseaux internes
```

Services trouvés : Identity, API Gateway, Player & Party, Matchmaking, Match &
Lobby, Web App React/Vite, watcher Rust, quatre instances PostgreSQL, Redis,
RabbitMQ, Caddy et observabilité. Le `community-service`, l'intégration
Cloudflare réelle et l'application Tauri 2 décrits dans le référentiel ne sont
pas présents dans ce dossier.

### Surface exposée

- Public à l'edge : SPA, assets, métadonnées OAuth/OIDC, login/consentement,
  callbacks OAuth et santé limitée.
- APIs utilisateur authentifiées : `/api/v2/players/**`,
  `/api/v2/parties/**`, `/api/v2/matchmaking/**`, `/api/v2/matches/**`.
- APIs techniques : `/api/v2/internal/players/**`,
  `/api/v2/watchers/**`, résultat générique et résultat bot, chacune protégée
  par un scope technique distinct.
- WebSockets : `/api/v2/realtime/party` et `/api/v2/realtime/matches`, avec JWT
  transporté comme sous-protocole, contrôle d'origine exact et autorisation
  d'abonnement côté service.
- Administration : aucun contrôleur métier administrateur public trouvé ;
  PostgreSQL, Redis et RabbitMQ ne publient pas de port hôte. Prometheus et
  Alertmanager publient leurs interfaces uniquement sur `127.0.0.1`.
- Rust : aucun `#[tauri::command]`, aucune capability Tauri, aucun plugin shell,
  aucune configuration updater. Le composant est un agent HTTP loopback Rust,
  pas une application Tauri dans l'état audité.

### Données et secrets

- `infra/.env` est ignoré par Git ; ses 16 secrets ont été validés sans être
  affichés. Le nouveau secret du result ingestor a été généré sans rotation des
  autres valeurs.
- Les secrets d'exemple utilisent uniquement `GENERATE_ON_FIRST_START`.
- Les clés JWT sont persistées dans un volume isolé et sont maintenant bornées
  à lecture/écriture propriétaire sur POSIX.
- Le dossier `backups/`, ignoré par Git, contient 8 dumps et 2 manifestes JSON
  réels. Leur contenu métier n'a pas été lu.
- Le balayage local haute confiance, hors `.env`, sauvegardes et artefacts, n'a
  trouvé aucun credential codé en dur. Les occurrences restantes sont des noms
  de variables, placeholders ou valeurs de tests manifestes.

### État Git et limite historique

Le dépôt n'a aucun commit (`HEAD` absent), aucun fichier suivi et tous les
fichiers sont non suivis. Il est donc impossible de rechercher un secret retiré
dans l'historique, d'attribuer les changements ou de garantir ce qui serait
effectivement livré. Ce point doit être résolu avant toute CI de confiance.

## 3. Threat model synthétique

Acteurs retenus : visiteur anonyme, utilisateur malveillant, compte ou bearer
token volé, navigateur compromis par XSS, watcher/binaire local compromis,
poste infecté, microservice ou conteneur compromis, détenteur de credentials
RabbitMQ/PostgreSQL, contributeur ou dépendance compromise et runner CI.

Actifs prioritaires : comptes Pinkward, identité Riot vérifiée, access/refresh
tokens, clé privée JWT, clients OAuth techniques, secrets de match et de lobby,
résultats classés/MMR, files RabbitMQ, bases par service, sauvegardes et chaîne
de livraison.

Frontières déterminantes : navigateur–edge, edge–gateway, gateway–service,
service–service, watcher–API, watcher–LCU loopback, service–base/broker,
CI–registry et sauvegarde–stockage. Le risque métier dominant est l'altération
du classement par confusion d'identité ou contournement du consensus watcher.

## 4. Synthèse des constats

| ID | Constat | Gravité | État |
|---|---|---:|---|
| PW-SEC-001 | Identités Riot/région d'un duel fournies par le navigateur | Élevée | Corrigé, testé |
| PW-SEC-002 | Jeton de match seul suffisant pour les routes watcher | Élevée | Corrigé, testé |
| PW-SEC-003 | Logout incomplet et session restaurable dans les autres onglets | Moyenne | Corrigé, build validé |
| PW-SEC-004 | Edge construit contourné par redirection vers Vite | Moyenne | Corrigé, contrôle statique |
| PW-SEC-005 | Origines WebSocket locales ajoutées implicitement | Moyenne | Corrigé, build validé |
| PW-SEC-006 | Permissions/producteurs RabbitMQ insuffisamment bornés | Moyenne | Corrigé, testé |
| PW-SEC-007 | Paramètres OAuth et JWT WebSocket journalisables par Caddy | Élevée | Corrigé, contrôle statique |
| PW-SEC-008 | CI partielle et références d'outils mutables | Moyenne | Corrigé en partie |
| PW-SEC-009 | Watcher capable d'appeler la route de résultat générique | Élevée | Corrigé, testé |
| PW-SEC-010 | Riot IDs d'autres joueurs surexposés au navigateur | Moyenne | Corrigé, testé |
| PW-SEC-011 | JavaScript inline du consentement incompatible avec la CSP | Faible | Corrigé, testé |
| PW-SEC-012 | Permissions du fichier de clés privées JWT non explicites | Moyenne | Corrigé, build validé |
| PW-SEC-013 | Identité locale sans défense production démontrée | Élevée prod. | Ouvert, bloquant |
| PW-SEC-014 | Secret OAuth watcher partagé dans un client distribué | Élevée prod. | Ouvert, bloquant |
| PW-SEC-015 | SCA Java et assurance images incomplètes | Élevée prod. | Ouvert, bloquant |
| PW-SEC-016 | Sauvegardes en clair et volume partagé en écriture | Moyenne | Ouvert |
| PW-SEC-017 | TLS, Cloudflare et pile réelle non validés | Moyenne | Ouvert, bloquant prod. |
| PW-SEC-018 | Absence de dépôt Git initialisé et de provenance | Moyenne supply chain | Ouvert |

## 5. Constats corrigés — détails et preuves

### PW-SEC-001 — identités de duel non autoritatives

**Sévérité / statut / composant.** Élevée, corrigé ; Match et Player.
**Fichiers / fonction.** `DuelChallengeService.java:32`,
`DuelIdentityClient.java`, `PlayerInternalController.java:24` ;
`POST /api/v2/matches/duels`.
**Description et cause.** Le corps client pouvait auparavant fournir les Riot
IDs et la région qui déterminaient le lobby et le résultat. La frontière
navigateur avait été traitée comme une source d'identité fiable.
**Préconditions / scénario.** Un utilisateur authentifié modifie la requête et
substitue une identité Riot ou une région.
**Impact.** Usurpation d'objet, incohérence de lobby et altération du MMR.
**Preuve.** Le chemin corrigé ne reçoit plus que `opponentId`, puis résout les
deux identités vérifiées auprès de Player Service avant persistance ;
`DuelChallengeServiceTest.java:36-37` vérifie que les valeurs persistées sont
celles du service et le rejet inter-région.
**CWE / OWASP.** CWE-639 ; ASVS 8.2.1/8.2.2 ; Top 10 2025 A01/A08 ; API1/API5 ;
WSTG-ATHZ-04.
**CVSS indicatif.** 3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:H/A:L` (7.6).
**Correctif.** Résolution server-side via client confidentiel
`pinkward-match`, scope minimal `service:profile:read`, timeout court, refus des
redirections et comparaison de région.
**Risque résiduel.** La qualité de la preuve Riot dépend encore du mécanisme de
liaison local ; la production devra utiliser le flux Riot approuvé.

### PW-SEC-002 — authentification watcher à un seul facteur applicatif

**Sévérité / statut / composant.** Élevée, corrigé ; Gateway, Match, watcher.
**Fichiers / endpoint.** `GatewaySecurityConfiguration.java:51-52`,
`DuelWatcherController.java:10-25`, `DuelWatcherService.java:123` ;
`/api/v2/watchers/duels/{matchId}/**`.
**Description / scénario.** Le secret éphémère remis au navigateur suffisait
auparavant pour se présenter comme watcher. Une XSS ou un navigateur compromis
pouvait publier une observation sans posséder l'identité technique.
**Impact.** Faux résultats et atteinte à l'intégrité du classement.
**Preuve et cause racine.** Le même secret servait au transport et à
l'autorisation. Le contrôleur exige maintenant simultanément
`SCOPE_service:duel:observe` et `X-Watcher-Token`; le service vérifie hash,
match, joueur, expiration et fraîcheur des observations.
**CWE / OWASP.** CWE-287/CWE-863 ; ASVS V8 ; A01/A08 ; API2/API5.
**CVSS indicatif.** 3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:L` (7.1).
**Tests.** Tests Gateway/Match et 17 tests Rust ; smoke duel mis à jour.
**Risque résiduel.** Le JWT technique repose sur un secret partagé distribué ;
voir PW-SEC-014.

### PW-SEC-003 — logout incomplet et inter-onglets

**Sévérité / statut / composant.** Moyenne, corrigé ; Web App/Identity.
**Fichiers / fonction.** `web-app/src/auth.ts:205-257`, en particulier
`handleRemoteSignOut` ligne 218 et `signOut` ligne 240.
**Description.** Le premier état supprimait seulement la session locale. Une
première correction révoquait le refresh token mais les onglets avertis ne
supprimaient que l'état React : un rechargement pouvait restaurer leur copie
dans `sessionStorage`.
**Scénario / impact.** Un utilisateur se déconnecte dans un onglet puis recharge
un autre onglet, qui continue avec son token tant qu'il reste valable.
**Cause / preuve.** Le callback de diffusion n'effaçait pas `SESSION_KEY` et
`FLOW_KEY`. `handleRemoteSignOut` effectue désormais cet effacement pour
`BroadcastChannel` et l'événement `storage`; `signOut` révoque aussi le refresh
token via `/oauth2/revoke`.
**CWE / OWASP.** CWE-613 ; API2 ; WSTG-SESS-06.
**Correctif / test.** Build TypeScript/Vite réussi. Aucun framework de test DOM
n'est présent ; la révocation bout en bout reste dans le test Docker à rejouer.
**Risque résiduel.** La révocation porte sur le refresh token courant, pas sur
toutes les sessions du compte ni sur un access token déjà volé.

### PW-SEC-004 — contournement du reverse proxy de production

**Sévérité / statut / composant.** Moyenne, corrigé ; Caddy/Web App.
**Fichier.** `infra/Caddyfile:44-45` et `web-app/Caddyfile`.
**Description.** La route par défaut envoyait le navigateur vers le serveur
Vite local, absent du lancement packagé et en dehors des en-têtes de l'edge.
**Impact.** Parcours cassé et politique de sécurité incohérente.
**CWE / OWASP.** CWE-16 ; ASVS V3 ; A02 ; API8 ; WSTG-CONF.
**Correctif / preuve.** Proxy direct vers `web-app:80`, CSP et en-têtes aux deux
niveaux ; `test-local-security.ps1` interdit le retour de la redirection.
**Risque résiduel.** Validation syntaxique par binaire Caddy non exécutée, car
Caddy n'est pas installé et Docker est arrêté.

### PW-SEC-005 — origines WebSocket implicites

**Sévérité / statut / composant.** Moyenne, corrigé ; Player et Match.
**Fichiers.** `PartyWebSocketConfiguration.java:19-35`,
`MatchWebSocketConfiguration.java:18-34`, `infra/compose.yml:149,241`.
**Description.** Les origines localhost étaient ajoutées au code quelle que soit
la configuration, créant une exception implicite en production.
**Impact.** Connexion cross-site depuis une origine non prévue sur une machine
cliente.
**CWE / OWASP.** CWE-942 ; ASVS V3 ; A01/A02 ; API8.
**Correctif.** Liste exacte, explicite, non vide et sans wildcard.
**Tests / résiduel.** Compilation et tests Java réussis ; test HTTP Origin réel
à rejouer avec Docker.

### PW-SEC-006 — intégrité RabbitMQ insuffisamment bornée

**Sévérité / statut / composant.** Moyenne, corrigé ; RabbitMQ et consommateurs.
**Fichiers.** `infra/compose.yml:419-420`,
`MatchFoundConsumer.java:63`, `MatchConfirmedConsumer.java:33`,
`MatchCancelledConsumer.java:33`, `PlayerRatingUpdatedConsumer.java:35` et
`DuelRatingUpdatedConsumer.java:35`.
**Description.** Les permissions de ressources n'empêchaient pas un service de
publier une routing key appartenant à l'autre, et les consommateurs ne
validaient pas toujours `producer`.
**Scénario / impact.** Un credential RabbitMQ de service compromis injecte un
événement métier accepté et modifie le cycle de match/MMR.
**CWE / OWASP.** CWE-345 ; ASVS V8 ; A08 ; API5/API10.
**Correctif.** Topic permissions `pinkward.matchmaking.*` et `pinkward.match.*`
séparées, validation stricte du producteur, type et version, files mortes et
idempotence conservées.
**Tests.** Tests consommateurs et assertion statique du bootstrap.
**Risque résiduel.** Trafic AMQP interne non chiffré dans Compose local ; TLS et
identités séparées restent requis sur un réseau de production non fiable.

### PW-SEC-007 — données d'authentification dans les logs Caddy

**Sévérité / statut / composant.** Élevée, corrigé ; Caddy/WebSocket/OAuth.
**Fichier.** `infra/Caddyfile:47-63`.
**Description.** Caddy pouvait journaliser la query OAuth complète et le header
`Sec-WebSocket-Protocol`, dans lequel l'application transporte un bearer JWT.
**Scénario.** Un lecteur de logs réutilise un code, un token ou un JWT encore
valide.
**Impact.** Compromission de session selon durée et audience du jeton.
**Cause / CWE / OWASP.** Redaction centrée sur les queries et oubli d'un canal
WebSocket non standard ; CWE-532 ; ASVS 14.2.1 ; A02 ; API8.
**CVSS indicatif.** 3.1 `AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:N` (6.7), dépendant
de l'accès aux logs.
**Correctif.** Redaction de `code`, `state`, tokens, mot de passe et
`client_secret`, plus suppression du header WebSocket dans l'encodeur de log.
**Test.** `test-local-security.ps1` exige les filtres.
**Risque résiduel.** Une requête réelle doit confirmer la forme du JSON généré
par la version Caddy embarquée.

### PW-SEC-008 — CI partielle et entrées mutables

**Sévérité / statut / composant.** Moyenne, partiellement corrigé ; GitHub
Actions/supply chain.
**Fichiers.** `.github/workflows/ci.yml:22-52`, `rust-toolchain.toml:1-4`,
`pom.xml:32-33`.
**Description.** La CI initiale omettait le web, Rust et certaines images. Les
actions et le canal Rust pouvaient aussi évoluer sans changement du dépôt.
**Impact.** Régression non détectée ou dépendance de build substituée.
**CWE / OWASP.** CWE-1104 ; Top 10 2025 A03 ; API10.
**Correctif.** Six images construites, audit/build npm, format/test/audit Rust,
Compose et tests Docker ; actions officielles épinglées par SHA ; Rust 1.98.0
figé ; `cargo-audit` 0.22.2 ; Spring Cloud 2025.1.3 et Testcontainers 2.0.5.
**Preuve.** CI rendue statiquement et commandes locales réussies.
**Risque résiduel.** Images de base par tags, pas de SBOM, scan, signature ou
attestation ; SCA Java non concluante, voir PW-SEC-015.

### PW-SEC-009 — bypass du consensus par le scope résultat générique

**Sévérité / statut / composant.** Élevée, corrigé ; Identity, Gateway, Match,
watcher et scripts de test.
**Fichiers / endpoints.** `AuthorizationServerConfiguration.java:179-195`,
`GatewaySecurityConfiguration.java:51-52,74-75`,
`DuelWatcherController.java:10`, `MatchController.java:117-119`,
`watcher/src/main.rs:609`.
**Description.** Après l'ajout du double contrôle sur `/watchers/**`, le même
client watcher conservait `service:match:result`. Il pouvait donc appeler
`POST /api/v2/matches/{matchId}/result`, chemin serveur qui ne demande ni
`X-Watcher-Token` ni consensus des deux joueurs.
**Préconditions / scénario.** Extraction du secret watcher depuis une
distribution, obtention d'un client-credentials token, puis soumission directe
d'un gagnant sur la route générique.
**Impact.** Contournement complet de la garantie d'intégrité ajoutée au duel et
fraude MMR.
**Cause racine.** Réutilisation d'un scope puissant entre agent non fiable et
ingestion serveur. CWE-269/CWE-863 ; ASVS V8 ; A01/A08 ; API2/API5.
**CVSS indicatif.** 3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:L` (7.1).
**Correctif.** Nouveau client confidentiel `pinkward-result-ingestor`, token de
2 minutes et scope exclusif `service:match:result`. Le watcher utilise
`service:duel:observe` et ne possède explicitement plus le scope générique. Les
smoke tests serveur utilisent le nouveau client.
**Tests.** `AuthorizationServerConfigurationTest.java:56-68` vérifie les scopes
positifs et négatifs ; 75 tests Java et 17 tests Rust passent.
**Risque résiduel.** Le secret result ingestor doit rester exclusivement sur un
service serveur ; le script local n'est pas un modèle de distribution prod.

### PW-SEC-010 — surexposition de Riot IDs

**Sévérité / statut / composant.** Moyenne, corrigé ; Player, Match, Web App.
**Fichiers.** `PlayerDirectoryController.java:14-36`,
`DuelChallengeSnapshot.java:6-12`, `web-app/src/App.tsx:211,1119`.
**Description.** L'annuaire authentifié et les snapshots d'invitation livraient
les Riot IDs des autres joueurs alors que l'interface n'avait besoin que du nom
d'affichage et du statut de liaison.
**Scénario / impact.** Recherche et collecte de l'identité de jeu d'utilisateurs
à partir d'un compte Pinkward ; perte de confidentialité et corrélation de
profils.
**CWE / OWASP.** CWE-200 ; ASVS V14 ; API3 Excessive Data Exposure.
**Correctif.** DTO public réduit à `playerId`, `displayName`, `riotLinked` ; Riot
IDs retirés des invitations. L'affectation interne du watcher conserve les
données strictement nécessaires.
**Tests.** `PlayerDirectoryControllerTest.java:15` verrouille les composants du
record ; build web réussi.
**Risque résiduel.** L'annuaire reste énumérable par recherche exacte et doit
conserver rate limiting, surveillance et politique de confidentialité.

### PW-SEC-011 — consentement OAuth bloqué par la CSP

**Sévérité / statut / composant.** Faible, corrigé ; Identity UI.
**Fichiers.** `IdentityUiController.java:109,144`,
`static/identity.js:1-6`, `AuthorizationServerConfiguration.java:64`.
**Description.** Le bouton d'annulation utilisait `onclick` alors que
`script-src 'self'` interdit le JavaScript inline. La fonction de sécurité
attendue ne s'exécutait donc pas sous la politique réellement servie.
**Impact.** Dégradation du contrôle utilisateur, sans élévation de privilège
démontrée. CWE-693 ; ASVS V3 ; A02.
**Correctif / test.** Gestionnaire externe same-origin, route statique permise
et proxifiée ; `IdentityUiControllerTest.java:62-63` exige le script et
l'absence d'inline handler.
**Risque résiduel.** Le rendu doit être rejoué dans un navigateur sur la pile.

### PW-SEC-012 — permissions de la clé privée JWT

**Sévérité / statut / composant.** Moyenne, corrigé ; Identity.
**Fichier.** `PersistentJwkSource.java:48-57,92-122`.
**Description.** Le keystore JSON contenant les clés privées RSA était écrit
atomiquement mais sans permissions de fichier explicitement minimales. Un
umask ou volume mal configuré pouvait le rendre lisible par un autre UID.
**Impact.** Lecture de clé et forge de tokens si une seconde identité accède au
volume. CWE-732 ; ASVS V6/V14 ; A02.
**Correctif.** Permission POSIX propriétaire lecture/écriture sur le fichier
existant et temporaire avant déplacement ; volume Docker isolé. Sous Windows,
le code conserve les ACL hôte car POSIX n'est pas disponible.
**Test.** Compilation et suite Identity réussies.
**Risque résiduel.** Le déploiement doit imposer UID dédié, ACL/volume et
protection des snapshots ; idéalement KMS/HSM pour une production sensible.

## 6. Risques ouverts

### PW-SEC-013 — identité locale non adaptée à Internet

**Sévérité / statut / composant.** Élevée en production, ouvert/bloquant ;
Identity.
**Fichiers.** `application.yml:42-45`,
`AuthorizationServerConfiguration.java:125`, `infra/compose.yml:100-103`.
**Description / preuve.** Deux comptes locaux proviennent de variables
d'environnement. Aucun workflow d'inscription/récupération, MFA, lockout,
détection de credential stuffing ou Riot RSO de production n'est présent.
**Scénario / impact.** Attaque répétée sur un compte exposé ou incapacité à
établir une identité Riot forte ; compromission de compte et du classement.
**CWE / OWASP.** CWE-307/CWE-308 ; ASVS V6 ; API2 ; WSTG-ATHN.
**Correctif recommandé.** Flux d'identité approuvé, rate limit durable par
compte/IP, backoff, MFA ou assurance équivalente, récupération robuste,
journalisation/détection et tests négatifs.
**Tests requis.** Échecs répétés, rotation/révocation, recovery, session fixation,
PKCE/state/nonce et abus distribué.
**Risque résiduel acceptable.** Comptes strictement locaux non exposés.

### PW-SEC-014 — secret watcher partagé dans un client distribué

**Sévérité / statut / composant.** Élevée en production, ouvert/bloquant ;
watcher/Identity.
**Fichiers.** `watcher/src/main.rs` (obtention des tokens techniques),
`AuthorizationServerConfiguration.java:187-195`.
**Description.** Le watcher utilise client credentials. Tout secret statique
embarqué dans un binaire distribué doit être considéré extractible ; le client
ne peut donc pas être confidentiel au sens OAuth.
**Scénario / impact.** Extraction puis émission de tokens watcher permettant de
sonder des affectations ou publier de fausses observations lorsqu'un jeton de
match est également obtenu. CWE-798/CWE-522 ; API2/API5.
**Correctif recommandé.** Enrôlement par installation/utilisateur, credentials
asymétriques rotatifs et révocables, mTLS/DPoP ou attestation selon modèle de
menace, scopes et audiences par appareil, détection et kill switch. Ne jamais
livrer le secret result ingestor.
**Mesure compensatoire actuelle.** Le watcher n'a plus le scope générique ; le
jeton éphémère lié au match et le consensus limitent l'impact.
**Tests requis.** Secret extrait, appareil révoqué, rejeu, mauvais joueur/match,
token expiré et deux agents collusifs.

### PW-SEC-015 — assurance dépendances et images incomplète

**Sévérité / statut / composant.** Élevée en production, ouvert/bloquant ; CI,
Java et conteneurs.
**Preuves.** `npm audit` rapporte 0 vulnérabilité sur 26 paquets et
`cargo audit` 0 vulnérabilité sur 166 dépendances/1 226 avis. En revanche,
Dependency-Check 13.0.0 s'arrête faute de clé/données NVD ; l'essai OSS Index a
reçu HTTP 401. Trivy, Grype, Syft, Gitleaks et Semgrep ne sont pas installés.
Les images utilisent des tags, pas des digests.
**Impact.** CVE Java ou image, substitution de base et absence de provenance
non détectées. CWE-1104 ; A03 ; API10.
**Correctif recommandé.** Alimenter Dependency-Check avec clé NVD ou miroir,
produire SBOM CycloneDX/SPDX, scanner les six images, bloquer selon politique
CVE, épingler par digest, signer/attester et vérifier à l'admission.
**Risque résiduel.** Les builds fonctionnels ne constituent pas une preuve
d'absence de vulnérabilité connue.

### PW-SEC-016 — sauvegardes locales en clair

**Sévérité / statut / composant.** Moyenne, ouvert ; PostgreSQL/scripts.
**Fichiers.** `infra/compose.yml:272,290,308,326`, `scripts/backup-postgres.ps1`,
`scripts/restore-postgres.ps1`, `backups/`.
**Description / preuve.** Les quatre bases montent le même répertoire hôte
`/backups` en lecture-écriture. Dix artefacts de sauvegarde réels existent et
aucun chiffrement applicatif/off-host n'est démontré.
**Scénario / impact.** Compromission d'un conteneur DB ou du poste : lecture,
altération ou suppression des sauvegardes de plusieurs domaines. CWE-311/732 ;
ASVS V14 ; A02.
**Correctif recommandé.** Export par identité/chemin séparé, chiffrement avec
clé hors du dépôt et de l'hôte, stockage immuable hors hôte, rétention,
hash/signature des manifestes et restauration testée.
**Risque résiduel.** Acceptable uniquement pour données locales fictives et
poste maîtrisé.

### PW-SEC-017 — edge réel et tests dynamiques indisponibles

**Sévérité / statut / composant.** Moyenne, ouvert et bloquant production ;
Caddy, Docker, Cloudflare.
**Preuve.** `docker info` échoue sur le pipe Docker Desktop ; Caddy CLI est
absent. Compose se rend correctement, mais aucun conteneur n'a été démarré.
**Non vérifié.** Syntaxe Caddy en exécution, en-têtes observés, HSTS sous HTTPS,
TLS, Cloudflare Authenticated Origin Pulls/mTLS, non-contournement de l'origin,
CORS, Origin WebSocket hostile, rate limits, révocation et parcours OAuth.
**OWASP.** ASVS V3/V13 ; A02 ; API8 ; WSTG-CONF/SESS.
**Correctif recommandé.** Démarrer Docker sur un environnement de test sans
données réelles, exécuter les smoke/MMR tests, tests négatifs HTTP/WebSocket,
scanner les images et tester la configuration Cloudflare séparément.
**Risque résiduel.** Une validation statique ne remplace pas l'observation du
comportement proxy/runtime.

### PW-SEC-018 — absence de provenance Git

**Sévérité / statut / composant.** Moyenne supply chain, ouvert ; dépôt/CI.
**Preuve.** `git rev-parse --verify HEAD` échoue, `git ls-files` retourne zéro.
**Impact.** Impossible de contrôler l'historique de secrets, protéger les
branches, revoir un diff, reproduire une release ou attester la source exacte.
**OWASP.** Top 10 2025 A03.
**Correctif recommandé.** Faire une revue du contenu, retirer ou archiver hors
dépôt les dumps, initialiser le dépôt, premier commit signé si possible,
protection de branche, revue obligatoire, secret scanning et artefacts liés au
SHA.
**Risque résiduel.** Tout contenu antérieur extérieur à ce dossier reste
inconnu.

## 7. Contrôles positifs observés

- OAuth2 Authorization Code + PKCE pour le client web public ; redirect URI
  exacte ; access token court et refresh token révoquable.
- Validation JWT dans Gateway et services avec issuer/audience, expiration et
  scopes ; contrôles métier d'appartenance/propriété dans les services.
- CSRF désactivé seulement sur les APIs bearer stateless ; formulaires Identity
  couverts par Spring Security et cookies de session côté serveur.
- Requêtes interservices bornées, sans redirection et avec clients/scopes
  distincts.
- Aucun sink React `dangerouslySetInnerHTML`, `eval`, `new Function` ou
  navigation externe générique repéré ; CSP restrictive sans `unsafe-eval`.
- Watcher confiné aux endpoints LCU/Live Client loopback, validation de chemins
  et refus des redirections ; mutations protégées par token local éphémère.
- Secrets aléatoires uniques, pas de fallback sensible Compose, ACL locale,
  réseaux segmentés ; les ports publiés sont tous liés à `127.0.0.1`.
- PostgreSQL séparé par service, chiffrement authentifié du mot de passe lobby,
  Redis authentifié, RabbitMQ sans guest distant, permissions par routing key,
  files mortes et validations d'enveloppe.
- Images applicatives exécutées avec utilisateurs non-root ; observabilité et
  alertes présentes.

Ces éléments réduisent le risque mais ne valent pas homologation de production.

## 8. Vérifications finales exécutées

| Vérification | Résultat final |
|---|---|
| `mvn --batch-mode --no-transfer-progress clean verify` | Succès — 75 tests, 0 échec |
| `npm ci` | Succès |
| `npm audit --audit-level=moderate` | Succès — 0 vulnérabilité, 26 paquets |
| `npm run build` | Succès — TypeScript + Vite production |
| `cargo fmt --all -- --check` | Succès |
| `cargo test --all-targets` | Succès — 17 tests, 0 échec |
| `cargo audit` | Succès — 166 dépendances, 1 226 avis, aucun finding |
| `docker compose --env-file infra/.env.example -f infra/compose.yml config --quiet` | Succès |
| `scripts/test-local-security.ps1` | Succès sans afficher de secret |
| Analyse syntaxique de tous les scripts PowerShell | Succès — 0 erreur |
| Recherche de secrets hors `.env`/backups/build | Aucun secret haute confiance |
| OWASP Dependency-Check 13.0.0 | Non concluant — clé/données NVD absentes |
| Sonatype OSS Index | Non concluant — HTTP 401 |
| Tests Docker, Caddy réel, images et Cloudflare | Non exécutés — Docker arrêté/outils absents |

Répartition des 75 tests Java : Contracts 7, Identity 4, Gateway 1, Player 13,
Matchmaking 18, Match 32.

## 9. Plan d'action priorisé

### P0 — avant exposition Internet

1. Concevoir l'identité production et ses défenses anti-abus (PW-SEC-013).
2. Remplacer le secret statique du watcher distribué par un enrôlement et des
   credentials révocables par installation (PW-SEC-014).
3. Rendre SCA Java, SBOM, scan d'images, digests et signature obligatoires
   (PW-SEC-015).
4. Démarrer une pile de test et exécuter tous les parcours dynamiques négatifs,
   puis valider TLS/Cloudflare/non-contournement de l'origin (PW-SEC-017).
5. Initialiser et protéger le dépôt Git avant de considérer la CI comme source
   de vérité (PW-SEC-018).

### P1 — avant données réelles

1. Chiffrer, isoler et externaliser les sauvegardes ; tester restauration et
   altération (PW-SEC-016).
2. Ajouter tests de révocation/logout tous onglets, mauvais scope, rejeu,
   mauvais match/joueur, origine hostile et redaction Caddy réelle.
3. Imposer en production : HTTPS/HSTS, cookies `Secure`, secrets via gestionnaire,
   UID/FS read-only/capabilities Linux minimales et chiffrement réseau interne
   quand la frontière le justifie.

### P2 — maintien

1. Ajouter Maven Enforcer (version minimale Maven/Java) et corriger le futur
   chargement dynamique Mockito signalé par Java 25.
2. Automatiser les mises à jour contrôlées et conserver les SHA d'actions.
3. Tenir une matrice ASVS par exigence, preuve CI, responsable et échéance.

## 10. Conclusion

Le nouveau défaut le plus important — capacité du watcher à contourner le
consensus via la route de résultat générique — est fermé et couvert par une
régression de scopes. Les fuites de jetons dans les logs, la surexposition des
Riot IDs, la déconnexion inter-onglets, la CSP du consentement, les permissions
de clé et la reproductibilité CI ont également été renforcées.

Le projet est cohérent pour du développement local. Il n'est pas prêt à être
exposé à Internet : cinq blocs de preuve restent ouverts, dont l'identité, le
modèle de credential du watcher, la chaîne logicielle Java/images, le runtime
edge et la provenance Git.

Références officielles : [OWASP ASVS 5.0](https://github.com/OWASP/ASVS),
[OWASP Top 10 2025](https://owasp.org/Top10/2025/),
[OWASP API Security Top 10 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/),
[OWASP WSTG 4.2](https://owasp.org/www-project-web-security-testing-guide/v42/).
