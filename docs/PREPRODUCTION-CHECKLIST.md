# Showdown V2 — checklist de préproduction

Cette checklist distingue les contrôles automatisés du dépôt des opérations qui
exigent une ressource externe. Une mise en ligne est refusée tant qu'une case
marquée « externe » n'est pas renseignée par l'exploitant.

## Contrôles automatisés

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

mvn verify
mvn -Psecurity-scan -DskipTests verify
Push-Location .\web-app; npm ci; npm audit --audit-level=high; Pop-Location
Push-Location .\watcher
cargo fmt --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked
cargo audit
Pop-Location

.\scripts\test-local-security.ps1
.\scripts\test-production-security.ps1
.\scripts\smoke-test.ps1
.\scripts\test-runtime-security.ps1
.\scripts\test-docker-mmr.ps1
~~~

La CI répète ces contrôles, exécute OWASP Dependency-Check et bloque aussi sur
les vulnérabilités HIGH/CRITICAL corrigibles trouvées par Trivy dans toutes les
images référencées par Compose, y compris PostgreSQL, Redis, RabbitMQ et les
composants d'observabilité durcis. Les propositions Dependabot couvrent Maven, npm, Cargo,
Docker et GitHub Actions. Les actions tierces sont référencées par SHA.

## Preuves locales du 30 août 2026

- Maven `verify` sous Java 21 : 88 tests, aucun échec ni test ignoré ;
- OWASP Dependency-Check 12.1.8 : analyse NVD, KEV et RetireJS réussie avec
  seuil bloquant CVSS 7, après mise à jour de Tomcat vers 11.0.25 ;
- Watcher Rust : formatage, Clippy sans avertissement, 29 tests et `cargo audit`
  réussis ;
- frontend : 119 tests Vitest, 21 parcours Playwright, lint, build et
  `npm audit` réussis ;
- tests Docker MMR : victoire 1v1 Glicko-2 de 1500 à 1662 et victoire 5v5
  TrueSkill de 1200 à 1301, projections RabbitMQ incluses ;
- tests runtime : isolation des scopes, CORS et origine WebSocket hostiles,
  limitation de débit, limitation des connexions, ACL RabbitMQ et masquage des
  tokens Caddy vérifiés ;
- Trivy : zéro vulnérabilité HIGH/CRITICAL corrigible dans toutes les images
  Compose auditées ;
- hygiène des images : utilisateurs applicatifs non-root et absence de `.env`,
  dump, sauvegarde, PDB ou exécutable Watcher distribué.

Sans `NVD_API_KEY`, la première synchronisation NVD peut prendre plus de
30 minutes. Le cache local accélère les exécutions suivantes, mais la clé reste
requise en CI pour une disponibilité fiable.

## GitHub (externe)

Après création du dépôt distant et premier `push`, appliquer la protection :

~~~powershell
.\scripts\configure-branch-protection.ps1 -Repository organisation/showdown
~~~

Elle interdit les force-push et suppressions, impose un historique linéaire,
une revue, la résolution des conversations et les cinq jobs `java`, `java-sca`,
`web`, `watcher` et `docker`. Ajouter le secret GitHub `NVD_API_KEY` pour rendre
la mise à jour OWASP rapide et fiable.

## Domaine, HTTPS et identité (externes)

- définir `SHOWDOWN_API_DOMAIN` avec le nom DNS du backend, sans schéma, et faire
  pointer ce DNS vers le serveur ;
- définir `SHOWDOWN_WEB_ORIGIN` avec l'origine HTTPS exacte du frontend autonome
  (actuellement `https://pinkward-showdown.guy-alexis60.chatgpt.site`) ;
- ouvrir uniquement 80/443 vers Caddy ;
- renseigner `infra/production.env` depuis le gestionnaire de secrets ;
- créer le fichier secret indiqué par `ALERTMANAGER_WEBHOOK_URL_FILE` avec l'URL
  du canal d'astreinte, puis déclencher et résoudre une alerte de test ;
- vérifier que le rendu Compose de production n'inclut pas `web-app` ; le profil
  `legacy-integrated-web` est réservé aux diagnostics locaux ;
- conserver `RESULT_INGESTOR_CLIENT_SECRET` dans le backend/CI uniquement ;
- enregistrer l'application chez le fournisseur OIDC choisi, renseigner
  `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID` et `OIDC_CLIENT_SECRET`, puis autoriser le
  callback `https://<SHOWDOWN_API_DOMAIN>/login/oauth2/code/production` ; le
  relais OIDC est intégré et les identités locales sont désactivées par la
  surcharge de production ;
- vérifier le certificat ACME, HSTS, les redirections OAuth exactes et les
  cookies `Secure`, `HttpOnly`, `SameSite=Lax` depuis le domaine final ; `Lax`
  conserve la session lors de la navigation OAuth entre le frontend et l'API ;
- signer l'exécutable Watcher Windows avant de le publier. Aucun binaire non
  signé ne doit être servi par le site.

## Données et sauvegardes

Si des données réelles sont conservées, installer `age`, choisir un destinataire
de chiffrement et un stockage hors hôte, puis programmer :

~~~powershell
.\scripts\backup-production.ps1 `
  -AgeRecipient 'age1...' `
  -OffsiteDirectory 'D:\Showdown-Offsite' `
  -RetentionDays 30
~~~

Tester périodiquement une restauration dans une stack isolée avec
`restore-production.ps1`. Les dumps locaux et archives chiffrées sont exclus de
Git et du contexte Docker.

## Décision de lancement

Le lancement est autorisé seulement si la CI est verte, la restauration a été
testée, Caddy ne journalise aucun token, les services internes sont injoignables
depuis Internet, les credentials Watcher sont individuels/révocables et le
fournisseur d'identité final est opérationnel.
