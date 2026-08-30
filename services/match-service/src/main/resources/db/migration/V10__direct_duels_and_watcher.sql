CREATE TABLE duel_challenges (
    id UUID PRIMARY KEY,
    challenger_id UUID NOT NULL,
    opponent_id UUID NOT NULL,
    challenger_riot_id VARCHAR(22) NOT NULL,
    opponent_riot_id VARCHAR(22) NOT NULL,
    region VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    match_id UUID REFERENCES matches(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_duel_challenge_players CHECK (challenger_id <> opponent_id),
    CONSTRAINT ck_duel_challenge_region CHECK (region IN ('EUW', 'EUNE', 'NA')),
    CONSTRAINT ck_duel_challenge_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_duel_challenge_participants
    ON duel_challenges (challenger_id, opponent_id, created_at DESC);
CREATE INDEX idx_duel_challenge_pending
    ON duel_challenges (status, expires_at) WHERE status = 'PENDING';

CREATE TABLE duel_watcher_tokens (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    role VARCHAR(8) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_duel_watcher_player UNIQUE (match_id, player_id),
    CONSTRAINT ck_duel_watcher_role CHECK (role IN ('HOST', 'GUEST'))
);

CREATE TABLE duel_watcher_observations (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL,
    objective VARCHAR(24) NOT NULL,
    winner_player_id UUID NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_duel_watcher_observation UNIQUE (match_id, reporter_id, objective),
    CONSTRAINT ck_duel_objective CHECK (objective IN ('FIRST_BLOOD', 'FIRST_TOWER', 'FIRST_TO_100_CS'))
);

CREATE INDEX idx_duel_watcher_consensus
    ON duel_watcher_observations (match_id, objective, winner_player_id);
