CREATE TABLE player_profiles (
    player_id UUID PRIMARY KEY,
    display_name VARCHAR(24) NOT NULL,
    region VARCHAR(16) NOT NULL,
    primary_role VARCHAR(16) NOT NULL,
    secondary_role VARCHAR(16) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    presence_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_player_region CHECK (region IN ('EUW', 'EUNE', 'NA')),
    CONSTRAINT ck_player_primary_role CHECK (primary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    CONSTRAINT ck_player_secondary_role CHECK (secondary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    CONSTRAINT ck_player_distinct_roles CHECK (primary_role <> secondary_role)
);

CREATE UNIQUE INDEX uq_player_display_name_ci ON player_profiles (lower(display_name));
CREATE INDEX idx_player_presence ON player_profiles (presence_expires_at);
