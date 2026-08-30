CREATE TABLE duel_rating_snapshots (
    player_id UUID PRIMARY KEY,
    mmr INTEGER NOT NULL CHECK (mmr >= 0),
    peak_mmr INTEGER NOT NULL CHECK (peak_mmr >= mmr),
    games INTEGER NOT NULL CHECK (games >= 1),
    season_code VARCHAR(16) NOT NULL,
    region VARCHAR(16) NOT NULL,
    rating DOUBLE PRECISION NOT NULL,
    rating_deviation DOUBLE PRECISION NOT NULL CHECK (rating_deviation > 0),
    volatility DOUBLE PRECISION NOT NULL CHECK (volatility > 0),
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_duel_rating_snapshots_region
    ON duel_rating_snapshots (season_code, region, mmr DESC);
