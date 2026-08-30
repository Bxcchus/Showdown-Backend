# Showdown — Pinkward Web V2

Showdown is the clean web-first V2 application. It is independent from the V1
repository and starts with one complete distributed slice instead of empty
placeholder services:

- Web App: React/TypeScript player interface served through Caddy;
- Realtime: two authenticated native WebSocket channels for Party and Match;
- Identity Service: OAuth 2.1/OIDC, public web client with PKCE and a technical
  Client Credentials client;
- API Gateway: JWT issuer/audience validation, deny-by-default routing, CORS,
  Redis rate limiting and correlation IDs;
- Player & Party Service: secured player profiles, TTL-based online presence,
  persistent groups, invitations, ready states and authenticated party queue
  orchestration in an isolated PostgreSQL database;
- Matchmaking Service: authenticated queue commands with primary/secondary
  roles, strict primary-role coverage with timed widening, PostgreSQL row
  locking, progressive TrueSkill search windows, party-safe team skill balance,
  ten-player reservation, transactional RabbitMQ outbox and idempotent
  confirmation/cancellation/rating consumers;
- Match Service: durable MATCH_FOUND consumer, DLQ, idempotent inbox, isolated
  PostgreSQL storage, unique lane assignment per team, secured ready-check and
  persistent web-lobby APIs with encrypted manual credentials, plus
  transactional confirmation/cancellation outboxes, match results, personal
  paginated/filterable history with full teammate/opponent rosters, aggregate
  statistics, regional seasonal leaderboard and an
  idempotent TrueSkill 5v5 ledger, plus an independent Glicko-2 1v1 ledger;
- Contracts: versioned event envelope shared without sharing persistence
  entities.

The lightweight Rust Watcher implements the current Riot/LCU bridge for ranked
1v1 verification. Community chat remains deferred until a concrete business use
case exists, which avoids introducing a fictitious service.

## Requirements

- JDK 21 (the build rejects another major version)
- Maven 3.9.6 or newer
- Docker Desktop for the local distributed stack

## Verify

~~~powershell
mvn --batch-mode --no-transfer-progress verify
docker compose --env-file infra/.env.example -f infra/compose.yml config --quiet
~~~

## Run locally

~~~powershell
.\scripts\start.ps1
~~~

On first start, the script creates a unique cryptographically random secret set
in the ignored `infra/.env` file and restricts its local filesystem permissions.
It never copies runnable credentials from `.env.example`.

Local entry point: http://127.0.0.1:8088

Use the generated local development account shown in `infra/.env` when the web login page
opens. The browser stores OAuth tokens only for the current tab session.

Rotate every local password, encryption key and technical OAuth secret without
deleting PostgreSQL, Redis or RabbitMQ data:

~~~powershell
.\scripts\local-secrets.ps1 -Mode Rotate
~~~

The rotation creates verified PostgreSQL backups, refuses to invalidate an
active encrypted lobby, changes credentials inside the persistent databases and
broker, recreates the stack, and never prints a secret. Restart the local
Watcher afterward so it reloads its technical credential.

Run the complete OAuth2 PKCE, player profile/presence and matchmaking queue
smoke test:

~~~powershell
.\scripts\smoke-test.ps1
~~~

Measure the configured Redis-backed rate limits and require real HTTP 429
responses:

~~~powershell
.\scripts\smoke-test.ps1 -MeasureRateLimits
~~~

Validate the full group path with five local players: creation, invitations,
ready confirmations and one atomic shared queue operation:

~~~powershell
.\scripts\smoke-test.ps1 -IncludePartyFlow
~~~

Run the complete local pipeline through RabbitMQ, Match Service, ready-check
decline and queue recovery:

~~~powershell
.\scripts\smoke-test.ps1 -IncludeMatchFlow
~~~

Validate the local five-second bot fill, player acceptance, confirmation event,
five unique roles per team, persistent web lobby, result, history and TrueSkill MMR:

~~~powershell
.\scripts\smoke-test.ps1 -IncludeBotConfirmationFlow
~~~

Run the two destructive-grade integration scenarios safely in a disposable
Docker project: a complete 1v1 through Glicko-2 and a complete 5v5 through
TrueSkill. Both tests verify OAuth, queueing, bot fill, ready-check, lobby,
Watcher-only result ingestion, history, the authoritative rating ledger,
RabbitMQ consumption and the MMR snapshot used by the next queue entry.

~~~powershell
.\scripts\test-docker-mmr.ps1
~~~

The runner generates temporary secrets, chooses a free loopback port, creates
isolated databases/Redis/RabbitMQ volumes, and removes the entire E2E project in
a `finally` block. It never reads or modifies the persistent
`pinkward-showdown` volumes. Use `-Scenario OneVsOne` or
`-Scenario FiveVsFive` to run only one path, and `-KeepStack` only when Docker
logs or database state need manual inspection.

In the local Docker profile, a real EUW player waiting alone is joined by bots
after five seconds. Bots are labelled in the web ready-check, receive roles and
accept automatically. The confirmed match opens a web lobby where a local
victory or defeat can be recorded. Only real players receive an MMR change;
repeated result submissions are idempotent. This helper is disabled by default in application
configuration and must never be enabled in production.

Every player owns a TrueSkill distribution `(μ, σ)`, initially `(25, 25/3)`.
The displayed MMR is the conservative exposure `μ - 3σ`, mapped onto the
existing 1200-point ladder. Matchmaking starts with a ±100 window around the
oldest waiting unit's TrueSkill mean, widens it by 50 every 15 seconds up to
±600, and then chooses the party-safe five-player partition with the smallest
team-mean difference. Rating updates
are replicated from Match Service through RabbitMQ; the browser never supplies
its own rating.

The web client no longer polls every two seconds. Invitations, party ready
states, match found/confirmed/expired and match results trigger targeted native
WebSocket events. The JWT is carried in a WebSocket subprotocol, never in the
URL, and is validated again by the destination service.

The regional leaderboard contains only placed players. S2026 and S2027 use
five placement games. At rollover, the completed rating is archived, half of
the TrueSkill mean advantage over 25 is retained and uncertainty is increased;
the new season's progression then starts at zero.

Ranked 1v1 uses a completely separate Glicko-2 distribution, initially
`rating = 1500`, `RD = 350` and `volatility = 0.06`, with `tau = 0.5`.
One completed duel is one immediate rating period. Both human opponents are
updated from the same pre-match snapshot. For local MMR tests, a bot is treated
as a fixed 1500-rated Glicko-2 opponent and only the human rating is persisted. A duel remains
provisional until placements are complete and RD has fallen to 160 or below.
The web History and regional Ranking screens expose 1v1 MMR, RD, volatility,
progression and results without modifying the 5v5 TrueSkill rating or its
matchmaking projection.

The local Party profile also turns an invitation to an unknown nickname into a
simulated team member. This makes the full group UI testable with the single
local OAuth account. The simulation is disabled by default outside Compose;
known real profiles always receive a normal invitation that they must accept.

Run an isolated 1,000-bot EUW load simulation. Generated data is cleaned at the
end:

~~~powershell
.\scripts\simulate-bots.ps1 -Count 1000 -Region EUW
~~~

Stop the local stack while keeping its database volumes:

~~~powershell
.\scripts\stop.ps1
~~~

See [the V2 architecture](docs/ARCHITECTURE.md) and the
[consolidated reference specification](docs/REFERENCE-SPECIFICATION.md).
[The operations runbook](docs/OPERATIONS.md) documents metrics, alerting,
backup and restore procedures.

OAuth clients are persisted in the isolated Identity PostgreSQL database. RSA
signing keys are persisted outside the image, rotate automatically and remain
published during a configurable overlap. Values in `infra/.env` are unique to
the local installation; an actual deployment must inject independently managed
secrets instead. Riot RSO remains deferred pending approval.

The product interface remains browser-only. Direct ranked duels use the small
lightweight native Rust agent in `watcher/` on both player PCs; its status window
minimizes to the Windows notification area, and the agent never exposes LCU
credentials to the Web App or backend. Start it with
`.\scripts\start-watcher.ps1`; the web profile creates a one-use Riot-link
challenge, reads the LCU identity from the loopback watcher, and completes the
player-bound challenge with the authenticated browser token. The watcher has no
Riot-link scope. Then open **Duel** and follow invitation → acceptance → watcher.
Both watchers must agree on first blood, first tower or first to 100 CS before
Glicko-2 is updated. Match creation resolves both verified Riot identities from
Player Service; the browser sends only the opponent's Showdown identifier.
Watcher submissions require both a match-scoped one-use token and the native
agent's short-lived `service:duel:observe` OAuth credential. Production uses a
different revocable credential per installation; the shared local client is
rejected by production-mode watchers. The distinct
`pinkward-result-ingestor` client is the only client allowed to submit a trusted
non-duel result.

For the current single-PC local demo, run
`.\scripts\start-watcher.ps1 -Simulation -RiotId 'Claude Code#JAVA'`. For a real
duel, run `.\scripts\start-watcher.ps1` on both PCs while the League clients are
open. The secondary local account credentials are read from `infra/.env`; use a
private browser window to keep its web session separate.
