ALTER TABLE player_profiles
    ADD COLUMN riot_puuid VARCHAR(128);

CREATE UNIQUE INDEX uq_player_riot_puuid
    ON player_profiles (riot_puuid)
    WHERE riot_puuid IS NOT NULL;

CREATE TABLE riot_link_challenges (
    challenge_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES player_profiles(player_id) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_riot_link_challenge_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_riot_link_challenge_consumed CHECK (
        consumed_at IS NULL OR consumed_at >= issued_at
    )
);

CREATE INDEX idx_riot_link_challenge_player
    ON riot_link_challenges (player_id, expires_at DESC);
