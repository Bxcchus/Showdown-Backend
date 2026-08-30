# Showdown Watcher

Agent Windows local avec une interface de statut native et légère. Il ne lit pas la mémoire du jeu et n'injecte aucun code : il utilise le LCU local pour le lobby et la Live Client Data API pour les objectifs du duel.

## Lancer

```powershell
.\scripts\start-watcher.ps1
```

Le service écoute uniquement sur `127.0.0.1:43991`. Les routes d'identité,
d'état et d'automatisation exigent une session locale éphémère que seules les
origines web explicitement autorisées peuvent demander.

La liaison Riot utilise un challenge backend à usage unique et valable 90
secondes. Le navigateur crée son challenge, ouvre une session locale éphémère
avec le Watcher, relit le PUUID, le Riot ID, l'icône et le niveau dans le LCU,
puis complète son propre challenge authentifié. Le Watcher ne possède plus le
scope permettant de finaliser la liaison et toutes ses routes sensibles exigent
`X-Showdown-Watcher-Token`. Cette liaison signifie « compte détecté localement » :
sans Riot RSO, elle ne constitue pas une preuve cryptographique de propriété face
à un utilisateur qui modifierait son propre binaire.

Pour un duel de test contre un bot, le Watcher récupère l'affectation et les
identifiants du lobby via une route technique, observe l'objectif dans la Live
Client Data API, puis envoie seulement `humanWon`, l'objectif et l'horodatage.
Match Service déduit l'équipe gagnante de son propre roster. Cette ingestion est
désactivée par défaut et doit être activée explicitement en environnement local.

Deux clients HTTP sont volontairement séparés : le backend utilise la
validation TLS normale et refuse HTTP hors loopback ; l'exception de certificat
local Riot est confinée à `https://127.0.0.1:<port LCU>` et
`https://127.0.0.1:2999`, sans proxy ni redirection.

La croix masque la fenêtre sans interrompre le watcher. Un double-clic sur son
icône dans la zone de notification rouvre la fenêtre. Le clic droit propose
« Ouvrir Pinkward Watcher » et « Quitter complètement » ; seule cette dernière
action arrête aussi le serveur local.

Pour tester sans client League :

```powershell
$env:SHOWDOWN_RIOT_ID='Claude Code#JAVA'
$env:SHOWDOWN_RIOT_PUUID='showdown-simulated-puuid'
$env:SHOWDOWN_WATCHER_SIMULATE='true'
cargo run
```

Le client `pinkward-watcher` partagé est strictement réservé au développement
local. Une exécution `-Production` refuse ce client et exige un client
`pinkward-watcher-installation-*` différent par installation. Ces clients ne
reçoivent que `service:duel:observe`; ils n'obtiennent ni liaison Riot, ni
résultat bot, ni résultat générique. Ils peuvent être créés et révoqués avec
`new-watcher-installation.ps1` et `revoke-watcher-installation.ps1`.

Les origines localhost sont ajoutées uniquement en développement. En production,
`SHOWDOWN_WEB_ORIGINS` doit contenir une liste exacte d'origines HTTPS et
l'adresse d'écoute reste obligatoirement loopback.

Avant toute mutation LCU d'un duel humain, le Watcher compare le PUUID et le Riot
ID complets du compte actuellement ouvert avec l'affectation signée par le
backend. Un cycle où un événement et le passage à 100 CS apparaissent ensemble
est placé en `REVIEW_REQUIRED` et ne publie aucun résultat.

Les routes LCU ne sont pas une API Riot officiellement supportée et peuvent
changer avec une mise à jour du client. L'adaptateur est volontairement isolé
dans ce binaire. Après chaque patch League, ouvrir le client puis exécuter :

```powershell
.\scripts\test-watcher-lcu-compatibility.ps1
```
