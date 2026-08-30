# Architecture V2

## Current implemented slice

~~~mermaid
flowchart LR
    C[Web browser] --> E[Caddy local edge]
    E --> W[React Web App]
    E --> I[Identity Service]
    E --> G[API Gateway]
    G --> PS[Player & Party Service]
    G --> MM[Matchmaking Service]
    G --> M[Match Service]
    G --> R[(Redis rate limits)]
    PS --> PP[(Player PostgreSQL)]
    MM --> P[(PostgreSQL)]
    MM -->|MATCH_FOUND| Q[(RabbitMQ)]
    Q -->|MATCH_FOUND| M
    M -->|MATCH_CANCELLED| Q
    Q -->|MATCH_CANCELLED| MM
    M -->|MATCH_CONFIRMED| Q
    Q -->|MATCH_CONFIRMED| MM
    M --> MP[(Match PostgreSQL)]
~~~

Identity, Gateway, Player, Matchmaking and Match are independent deployables
because each owns real behavior. Future services are introduced by a vertical
business slice, never as health-only shells.

## Security boundary

- Identity signs RS256 access tokens and exposes JWKS.
- The Web App is a public client and uses Authorization Code with PKCE.
- Technical clients use distinct Client Credentials.
- Gateway and every resource service validate issuer, audience and timestamps.
- Player profile reads require `profile:read`; updates and presence commands
  require `profile:write` at both Gateway and service boundaries.
- Matchmaking derives player identity from JWT subject.
- Match verifies membership from the JWT subject before ready-check updates.
- Unknown Gateway routes are denied.

Identity persists OAuth registered clients in its isolated PostgreSQL database.
It stores private RSA-3072 JWKs in a dedicated non-image volume, selects the
active `kid` when signing, rotates after 30 days by default and keeps previous
public keys available for a seven-day verification overlap. A restart therefore
does not invalidate active access tokens. Production must place the key store
on encrypted durable storage and inject credentials from a secret manager.

## Player profiles and presence

Player Service owns public nickname, favourite region and primary/secondary
role preferences. Its PostgreSQL schema enforces supported regions and roles,
distinct role preferences and case-insensitive nickname uniqueness. The web
profile is created lazily from the authenticated JWT subject and identity name.

Opening the authenticated web app marks the player online. It sends a presence
heartbeat every twenty seconds; the server applies a forty-five-second TTL so
a browser crash or closed tab cannot leave a permanent online state. Explicit
web logout marks the profile offline immediately. Matchmaking copies the saved
preferences into the local queue selector but remains the authority for the
roles submitted with each queue entry.

## Parties and shared matchmaking

Player & Party owns group membership, leadership, invitations and ready state.
Party commands require `party:manage`. A leader may start a search only with
two to five members and after every member has confirmed ready. Player Service
then obtains a short-lived Client Credentials token for the dedicated
`pinkward-player` client and calls Matchmaking directly with the minimal
`service:queue:party` scope.

Matchmaking inserts the complete roster in one transaction with a common
`party_id`. Selection treats every party as an indivisible unit and only forms
a reservation when the units can be partitioned into two teams of five. The
ordered MATCH_FOUND roster therefore keeps every group on the same team. A
group cancellation also removes all non-reserved party entries atomically.

In local Compose only, an unknown invited nickname becomes a simulated member
whose ready state the leader can control. These members are marked as local
bots in MATCH_FOUND and auto-accept the ready check. Real profiles always use a
pending invitation accepted by the invitee.

## Matchmaking consistency

Queue commands are idempotent by player plus Idempotency-Key. A worker reserves
ten rows with PostgreSQL FOR UPDATE SKIP LOCKED. Reservation and outbox event
are committed together. A separate publisher sends the versioned event to
RabbitMQ and records publication time.

RabbitMQ delivery remains at-least-once. Match Service consumes MATCH_FOUND
through a durable queue, uses an inbox keyed by eventId, persists the match in
its own PostgreSQL database and sends poison messages to a dedicated DLQ.
Decline and timeout decisions write MATCH_CANCELLED to a transactional outbox;
Matchmaking consumes it through its own durable queue, inbox and DLQ. A player
who declines leaves matchmaking while the other nine return to the queue. On a
timeout, only players who had accepted return to the queue, which prevents the
same incomplete roster from being matched forever.

The local Compose profile enables a test-only EUW bot filler. When at least one
real player has waited five seconds and fewer than ten players are queued, it
creates only the number of bots required to complete the roster. Bots are
identified in MATCH_FOUND, auto-accept the ready check and are never requeued
after cancellation. The feature defaults to disabled outside local Compose.

When the tenth player accepts, Match Service commits MATCH_CONFIRMED through
its outbox. Matchmaking consumes the event idempotently and removes the ten
reserved queue entries. Confirmed matches remain as history but are no longer
returned by the current ready-check endpoint, so the web interface returns to
the home screen instead of remaining blocked.

## Results and isolated 5v5/1v1 ratings

In the local web profile, a participant closes a confirmed lobby by reporting
the winning team. Match Service verifies membership, persists the winner and
completion time, then updates every non-bot rating in the same transaction.
The rating ledger has a unique match/player constraint, so a repeated request
cannot apply the MMR change twice. Bots never own ratings.

Each player starts with a TrueSkill distribution `μ = 25` and `σ = 25/3`.
A completed 5v5 is evaluated as two teams of five in one no-draw Bayesian
update, using `β = 25/6` and the dynamics factor `τ = 25/300`. Bots participate
in the team performance distribution with the initial skill but never own a
persistent rating. The public MMR is a non-negative projection of conservative
exposure `μ - 3σ` onto the existing 1200-point ladder. Personal history is
paginated, filterable by role, region and result, and exposes the result,
assigned team and role, previous rating, delta and new rating. Its detailed
view returns the five teammates and five opponents with their assigned roles.
Aggregate statistics
expose wins, losses, win rate, peak MMR, `μ`, `σ`, recent form and games by role. KDA is
intentionally absent until the future Tauri 2 companion or an authoritative
Riot integration can provide trustworthy match telemetry.

`ONE_V_ONE` results follow a separate Glicko-2 path. Each duellist starts at
rating 1500, rating deviation 350 and volatility 0.06; `tau` is 0.5. One duel
is treated as one immediate rating period and both players are calculated from
their distributions before the match. Match/player uniqueness in the duel
ledger makes result processing idempotent. Normal rated duels require exactly
two humans, one per team. The local test profile may replace one opponent with a
bot represented by the initial 1500/350/0.06 Glicko-2 distribution; only the
human distribution and ledger entry are persisted. Duel ratings never publish
`PLAYER_RATING_UPDATED`, because that event feeds the 5v5 matchmaking projection
only.

The 1v1 API exposes personal MMR, RD, volatility, placements, progression and
a regional leaderboard. A player remains provisional while placement games
are missing or RD is above 160. Season rollover archives the duel distribution,
soft-resets rating toward 1500 and widens uncertainty. The browser presents the
duel ladder alongside, but never merges it with, the TrueSkill ladder.

Every committed human rating also writes a PLAYER_RATING_UPDATED event to the
Match outbox. Matchmaking consumes it through a durable queue, idempotent inbox
and DLQ, then maintains a local read projection. Queue entries snapshot that
server-owned MMR, `μ` and `σ` at join time; no client-provided skill is accepted.

The search window begins at ±100 around the oldest queue unit's average `μ`
(expressed on the MMR scale), widens by 50 every 15 seconds and caps at ±600.
A party is one indivisible unit and simulated/local bots inherit the human
group's reference distribution. After ten
compatible players have been selected, every valid five-player partition is
evaluated and the smallest difference between team `μ` totals wins. Stable
queue order breaks equal solutions, while role coverage and timed role widening
continue to apply independently.

## Client boundary and 1v1 watcher

The active product UI remains fully web. Ranked 1v1 additionally uses the
lightweight native Rust `showdown-watcher` on each player's Windows PC. Its small
status window and notification-area menu do not contain matchmaking controls:
the agent listens only on `127.0.0.1`, discovers
the local Riot ID, automates the agreed custom lobby and observes the three duel
win conditions. The browser and backend never receive LCU credentials. Profile
linking uses a 90-second, one-use backend challenge: the browser reads the local
LCU identity from its loopback watcher and completes its own challenge with the
authenticated `profile:write` token. The watcher cannot complete a link and no
longer owns `service:profile:link`.

The host watcher creates the named 1v1 lobby, resolves and invites the guest,
waits for two members and starts champion select. The guest first tries a direct
join by lobby name/password, then accepts the received invitation as fallback.
LCU endpoints are intentionally isolated because Riot does not guarantee their
stability. Live game observations use the local Live Client Data API and never
read process memory or inject code.

The browser obtains a one-use raw watcher token after an accepted challenge and
passes it to its own loopback watcher. Match Service stores only SHA-256 hashes;
tokens are scoped to one match/player, rotate on reissue and expire after two
hours. Every watcher API call additionally requires the watcher's technical
OAuth client credential with `service:duel:observe`; possession of a browser-
issued match token is therefore insufficient to impersonate the native agent.
The OAuth token is renewed before expiry and once after a `401`. Public builds
require a distinct `pinkward-watcher-installation-*` credential whose removal
from server configuration revokes the installation; the shared local client is
not accepted in production mode.
Observation timestamps are accepted only within a narrow freshness window. A
ranked result is committed only after both player watchers report the
same winner for the same first objective (`FIRST_BLOOD`, `FIRST_TOWER` or
`FIRST_TO_100_CS`). A disagreement leaves the duel unresolved for review and
cannot update Glicko-2.

Direct-duel creation accepts only the opponent's Showdown UUID. Match Service
resolves both verified Riot identities through Player Service using its own
`service:profile:read` client credential, rejects cross-region pairs and stores
the server-owned Riot IDs and region. The browser cannot choose the identities
that drive lobby automation or result consensus.

The generic trusted-result route is deliberately outside the watcher trust
domain. Only `pinkward-result-ingestor` can obtain `service:match:result`; the
watcher client cannot request that scope and therefore cannot bypass duel
consensus. Public directory and challenge responses expose only Showdown IDs,
display names, region and Riot-link status—not another player's Riot ID.

Local bot tests use a separate service scope. The browser can neither fetch the
bot lobby assignment nor submit `/bot-result`; both paths are hidden by the web
BFF and enforced again by Gateway. The watcher fetches the encrypted-at-rest
lobby assignment, observes the local game and submits `humanWon`, the objective
and a fresh timestamp. Match Service validates the one-human/one-bot roster and
derives the winner from server-owned teams before applying Glicko-2.

The watcher also has two non-interchangeable HTTP clients. Backend calls use
normal certificate validation, no redirects and HTTPS for every non-loopback
origin. The only certificate exception is a no-proxy/no-redirect Riot-local
client restricted to `127.0.0.1` LCU and Live Client Data endpoints.

## Native WebSocket realtime

The browser opens `/api/v2/realtime/party` and `/api/v2/realtime/matches`
through Caddy and Gateway. HTTP upgrade is public at Gateway because browser
WebSocket APIs cannot attach an Authorization header; the access token instead
travels in a `bearer.<JWT>` WebSocket subprotocol and is validated by the owning
service before the session is accepted. The token is never placed in a URL.

Party mutations notify only current members and pending invitees after commit.
Match creation, ready state, confirmation, expiry and result notify only the ten
participants after commit. Events invalidate the relevant REST snapshots; REST
remains the authoritative recovery path after reconnection. The former two-second
polling loop has been removed. A broker relay remains required before running
multiple replicas of Player or Match.

## Seasons and regional leaderboard

Rating seasons are stored with start/end instants, placement count and reset
factor. Five games are required for placement. The leaderboard is isolated by
season and region, ordered by conservative TrueSkill MMR, and exposes rank,
win rate and progression from the player's season starting rating.

At rollover, the final rating is archived. The configured 0.5 soft reset keeps
half the distance of `μ` from 25 and increases `σ`; games, wins, losses and
progression restart while the previous season remains auditable.

## Production-hardening baseline

Redis requires a password, protected mode, AOF `everysec`, a bounded 128 MiB
memory budget and an explicit eviction policy. Destructive `FLUSHALL` and
`FLUSHDB` commands are disabled. RabbitMQ uses the dedicated `Pinkward` vhost,
one administrator and one credential per consuming service; service accounts
are restricted to the shared event exchanges and their own queue namespaces.
Queues, DLQs and messages used by the event pipeline are durable.

Actuator management listeners use separate internal ports. Prometheus scrapes
the five JVM services, RabbitMQ, Redis and four PostgreSQL exporters. Only
Caddy, Prometheus and Alertmanager are bound to host loopback; Caddy exposes the
web/API edge and Gateway readiness, not metrics. Alert rules cover target loss, sustained 5xx,
rate-limit pressure, RabbitMQ backlog, Redis memory and PostgreSQL availability.

All four PostgreSQL databases can be dumped as verified compressed archives
with SHA-256 manifests and a retention policy. Restore is deliberately explicit
and scoped to one selected database. The local stack proves the mechanism;
production still requires encrypted off-host copies, a real Alertmanager
receiver, scheduled restore drills, and TLS when Redis or RabbitMQ cross a host
or trust boundary.
