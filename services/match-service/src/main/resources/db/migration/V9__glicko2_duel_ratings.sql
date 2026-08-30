CREATE TABLE duel_player_ratings (
    player_id UUID PRIMARY KEY,
    rating DOUBLE PRECISION NOT NULL DEFAULT 1500.0,
    rating_deviation DOUBLE PRECISION NOT NULL DEFAULT 350.0,
    volatility DOUBLE PRECISION NOT NULL DEFAULT 0.06,
    peak_rating INTEGER NOT NULL DEFAULT 1500,
    games INTEGER NOT NULL DEFAULT 0,
    wins INTEGER NOT NULL DEFAULT 0,
    losses INTEGER NOT NULL DEFAULT 0,
    season_code VARCHAR(16) NOT NULL REFERENCES rating_seasons(code),
    region VARCHAR(16) NOT NULL,
    season_start_rating INTEGER NOT NULL DEFAULT 1500,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_duel_rating_deviation CHECK (rating_deviation > 0 AND rating_deviation <= 350),
    CONSTRAINT ck_duel_rating_volatility CHECK (volatility > 0),
    CONSTRAINT ck_duel_rating_games CHECK (games >= 0 AND wins >= 0 AND losses >= 0 AND wins + losses = games)
);

CREATE TABLE duel_rating_changes (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    previous_mmr INTEGER NOT NULL,
    previous_peak_rating INTEGER NOT NULL,
    rating_delta INTEGER NOT NULL,
    new_mmr INTEGER NOT NULL,
    previous_rating DOUBLE PRECISION NOT NULL,
    previous_deviation DOUBLE PRECISION NOT NULL,
    previous_volatility DOUBLE PRECISION NOT NULL,
    new_rating DOUBLE PRECISION NOT NULL,
    new_deviation DOUBLE PRECISION NOT NULL,
    new_volatility DOUBLE PRECISION NOT NULL,
    season_code VARCHAR(16) NOT NULL REFERENCES rating_seasons(code),
    region VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_duel_rating_change UNIQUE (match_id, player_id),
    CONSTRAINT ck_duel_change_previous_deviation CHECK (previous_deviation > 0),
    CONSTRAINT ck_duel_change_new_deviation CHECK (new_deviation > 0),
    CONSTRAINT ck_duel_change_previous_volatility CHECK (previous_volatility > 0),
    CONSTRAINT ck_duel_change_new_volatility CHECK (new_volatility > 0)
);

CREATE TABLE duel_season_rating_archives (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL,
    season_code VARCHAR(16) NOT NULL REFERENCES rating_seasons(code),
    region VARCHAR(16) NOT NULL,
    final_mmr INTEGER NOT NULL,
    peak_rating INTEGER NOT NULL,
    games INTEGER NOT NULL,
    wins INTEGER NOT NULL,
    losses INTEGER NOT NULL,
    rating DOUBLE PRECISION NOT NULL,
    rating_deviation DOUBLE PRECISION NOT NULL,
    volatility DOUBLE PRECISION NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_duel_season_rating_archive UNIQUE (player_id, season_code, region)
);

CREATE INDEX idx_duel_rating_leaderboard
    ON duel_player_ratings (season_code, region, rating DESC, updated_at ASC);
CREATE INDEX idx_duel_rating_change_player
    ON duel_rating_changes (season_code, region, player_id, created_at DESC);
