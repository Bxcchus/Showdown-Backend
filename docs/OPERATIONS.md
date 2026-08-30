# Showdown V2 — guide d’exploitation

## Démarrage et contrôles

~~~powershell
.\scripts\start.ps1
.\scripts\smoke-test.ps1
.\scripts\smoke-test.ps1 -MeasureRateLimits
.\scripts\smoke-test.ps1 -IncludeBotConfirmationFlow
~~~

Points d’accès locaux :

- application : `http://127.0.0.1:8088` ;
- Prometheus : `http://127.0.0.1:9090` ;
- Alertmanager : `http://127.0.0.1:9093`.

Le port Caddy et les interfaces d’observabilité sont publiés uniquement sur
loopback IPv4. Les services applicatifs, PostgreSQL, Redis et RabbitMQ ne sont
pas publiés sur l’hôte.

En production, le frontend est déployé séparément. Préparer l'environnement à
partir de `infra/production.env.example`, puis définir :

- `SHOWDOWN_API_DOMAIN`, nom DNS du backend sans `https://` ;
- `SHOWDOWN_WEB_ORIGIN`, origine HTTPS exacte du frontend, sans slash final.
- `OIDC_ISSUER_URI`, URL HTTPS de découverte du fournisseur d'identité ;
- `OIDC_CLIENT_ID` et `OIDC_CLIENT_SECRET`, credential confidentiel enregistré
  avec le callback `https://<SHOWDOWN_API_DOMAIN>/login/oauth2/code/production`.

Valider avant démarrage :

~~~powershell
.\scripts\test-production-security.ps1 -EnvironmentFile .\infra\production.env
docker compose --env-file .\infra\production.env `
  -f .\infra\compose.yml -f .\infra\compose.production.yml config --quiet
~~~

Le profil production par défaut ne crée pas `web-app` et Caddy ne route que
l'identité et `/api/*`. Les origines OAuth, CORS et WebSocket doivent toutes
correspondre au frontend autonome configuré. Les comptes locaux sont désactivés
et `/login` redirige vers le fournisseur OIDC externe.

## Secrets locaux

`scripts/start.ps1` génère au premier lancement seize valeurs Base64URL
indépendantes à partir d’un CSPRNG. `infra/.env.example` ne contient que les
sentinelles non exécutables `GENERATE_ON_FIRST_START`; `infra/.env` est ignoré
par Git et Docker puis limité par ACL à l’utilisateur courant, SYSTEM et les
administrateurs locaux.

Pour remplacer les secrets d’une pile déjà initialisée :

~~~powershell
.\scripts\local-secrets.ps1 -Mode Rotate
.\scripts\test-local-security.ps1
~~~

La rotation sauvegarde d’abord les quatre bases, arrête les consommateurs,
refuse de changer la clé AES lorsqu’un lobby `CONFIRMED` existe, exécute les
`ALTER ROLE` PostgreSQL et `rabbitmqctl change_password`, recrée Redis, puis
redémarre la pile. Une simple modification manuelle de `.env` ne change pas les
identifiants persistés dans PostgreSQL ou RabbitMQ et rendrait la pile
indisponible. Aucun fichier de sauvegarde plaintext des anciens secrets n’est
créé. Le Watcher doit être redémarré après rotation.

## Identité

Les clients OAuth sont persistés dans `pinkward_identity`. Le magasin JWK privé
est monté dans le volume `identity-keys`. Les réglages sont :

- `JWT_ROTATION_AGE`, 30 jours par défaut ;
- `JWT_KEY_OVERLAP`, 7 jours par défaut ;
- `JWT_KEY_STORE`, `/var/lib/showdown-identity/jwks.json` dans le conteneur.

La clé active signe les nouveaux JWT avec son `kid`. Les anciennes clés restent
dans JWKS pendant l’overlap. Sauvegarder la base Identity et le magasin de clés ;
hors environnement local, les secrets doivent provenir d’un gestionnaire dédié.

## Redis et RabbitMQ

Redis impose une authentification, AOF `everysec`, `protected-mode yes`, 128 MiB
et `allkeys-lru`. `FLUSHALL` et `FLUSHDB` sont désactivés.

RabbitMQ utilise le vhost `Pinkward`. `matchmaking_service` et `match_service`
peuvent déclarer et utiliser l’exchange d’événements, puis uniquement lire,
écrire et configurer leurs propres files. Le bootstrap est idempotent et attend
que l’API d’administration soit réellement disponible.

## Métriques, limites et alertes

Les budgets locaux sont injectables avec :

- `RATE_LIMIT_PLAYERS_REPLENISH` / `RATE_LIMIT_PLAYERS_BURST` ;
- `RATE_LIMIT_PARTIES_REPLENISH` / `RATE_LIMIT_PARTIES_BURST` ;
- `RATE_LIMIT_MATCHMAKING_REPLENISH` / `RATE_LIMIT_MATCHMAKING_BURST` ;
- `RATE_LIMIT_MATCHES_REPLENISH` / `RATE_LIMIT_MATCHES_BURST`.

Le Gateway exporte les requêtes et durées par route, les rejets 429 et les 5xx.
Les règles versionnées alertent aussi sur les cibles absentes, le backlog
RabbitMQ, la mémoire Redis et les bases indisponibles. Le receiver local
Alertmanager est volontairement neutre : brancher le canal d’astreinte réel en
production.

Le profil production remplace ce receiver local par
`infra/observability/alertmanager.production.yml`. Écrire exclusivement l'URL
du webhook dans le fichier hôte référencé par `ALERTMANAGER_WEBHOOK_URL_FILE`,
limiter ses permissions, puis vérifier une alerte de test et sa résolution. Le
secret n'est ni placé dans Compose, ni ajouté au dépôt, ni exposé dans les logs.

## Sauvegarde et restauration PostgreSQL

~~~powershell
.\scripts\backup-postgres.ps1 -RetentionDays 14
~~~

La commande crée quatre archives custom compressées dans `backups/`, vérifie
chaque archive avec `pg_restore --list`, puis écrit un manifeste SHA-256.

Une restauration remplace les objets de la base choisie et exige une
confirmation explicite :

~~~powershell
.\scripts\restore-postgres.ps1 `
  -Database matches `
  -BackupFile .\backups\matches-YYYYMMDDTHHMMSSZ.dump `
  -Force
.\scripts\smoke-test.ps1
~~~

En production, copier les archives chiffrées vers un stockage hors hôte,
définir RPO/RTO, programmer les sauvegardes et tester périodiquement une
restauration dans un environnement isolé.

Le flux de production chiffre avant écriture sur le stockage hors hôte :

~~~powershell
.\scripts\backup-production.ps1 `
  -AgeRecipient 'age1...' `
  -OffsiteDirectory 'D:\Showdown-Offsite' `
  -RetentionDays 30

.\scripts\restore-production.ps1 `
  -ArchiveFile 'D:\Showdown-Offsite\showdown-YYYYMMDDTHHMMSSZ.tar.age' `
  -AgeIdentityFile 'D:\Secrets\showdown-backup-key.txt' `
  -Database matches `
  -Force
~~~

Voir aussi [la checklist de préproduction](PREPRODUCTION-CHECKLIST.md).

## Limites encore assumées

- KDA absent tant que Riot ou le companion Tauri 2 ne fournit pas de télémétrie
  autoritaire ;
- Riot RSO soumis à approbation ;
- pas encore de Grafana, Loki ni OpenTelemetry ;
- TLS Redis/RabbitMQ obligatoire dès qu’un trafic sort des réseaux Docker
  privés ou traverse une frontière de confiance.
