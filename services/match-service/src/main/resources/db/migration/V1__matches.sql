CREATE TABLE matches (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    region VARCHAR(16) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    ready_deadline TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_match_status CHECK (status IN ('READY_CHECK', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
);

CREATE TABLE match_players (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    team VARCHAR(8) NOT NULL,
    ready_state VARCHAR(16) NOT NULL,
    CONSTRAINT uq_match_player UNIQUE (match_id, player_id),
    CONSTRAINT ck_match_team CHECK (team IN ('BLUE', 'RED')),
    CONSTRAINT ck_ready_state CHECK (ready_state IN ('PENDING', 'ACCEPTED', 'DECLINED'))
);

CREATE INDEX idx_match_player_lookup ON match_players (player_id, match_id);

CREATE TABLE inbox_events (
    event_id UUID PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL
);
