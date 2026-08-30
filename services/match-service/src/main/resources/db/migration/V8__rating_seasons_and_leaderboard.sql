CREATE TABLE rating_seasons (
    code VARCHAR(16) PRIMARY KEY,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    placement_games INTEGER NOT NULL DEFAULT 5,
    reset_factor DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    CONSTRAINT ck_rating_season_dates CHECK (ends_at > starts_at),
    CONSTRAINT ck_rating_season_placements CHECK (placement_games >= 0),
    CONSTRAINT ck_rating_season_reset CHECK (reset_factor >= 0 AND reset_factor <= 1)
);

INSERT INTO rating_seasons (code, starts_at, ends_at, placement_games, reset_factor) VALUES
    ('S2026', '2026-01-01T00:00:00Z', '2027-01-01T00:00:00Z', 5, 0.5),
    ('S2027', '2027-01-01T00:00:00Z', '2028-01-01T00:00:00Z', 5, 0.5);

ALTER TABLE player_ratings
    ADD COLUMN season_code VARCHAR(16) NOT NULL DEFAULT 'S2026' REFERENCES rating_seasons(code),
    ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT 'EUW',
    ADD COLUMN season_start_rating INTEGER NOT NULL DEFAULT 1200;

UPDATE player_ratings SET season_start_rating = 1200;

UPDATE player_ratings rating
SET region = COALESCE((
    SELECT match.region
    FROM rating_changes change
    JOIN matches match ON match.id = change.match_id
    WHERE change.player_id = rating.player_id
    ORDER BY change.created_at DESC
    LIMIT 1
), 'EUW');

ALTER TABLE rating_changes
    ADD COLUMN season_code VARCHAR(16) NOT NULL DEFAULT 'S2026' REFERENCES rating_seasons(code),
    ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT 'EUW';

UPDATE rating_changes change
SET region = match.region
FROM matches match
WHERE match.id = change.match_id;

CREATE TABLE season_rating_archives (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL,
    season_code VARCHAR(16) NOT NULL REFERENCES rating_seasons(code),
    region VARCHAR(16) NOT NULL,
    final_rating INTEGER NOT NULL,
    peak_rating INTEGER NOT NULL,
    games INTEGER NOT NULL,
    wins INTEGER NOT NULL,
    losses INTEGER NOT NULL,
    skill_mean DOUBLE PRECISION NOT NULL,
    skill_deviation DOUBLE PRECISION NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_season_rating_archive UNIQUE (player_id, season_code, region)
);

CREATE INDEX idx_player_rating_leaderboard
    ON player_ratings (season_code, region, rating DESC, updated_at ASC);
CREATE INDEX idx_rating_change_season_player
    ON rating_changes (season_code, region, player_id, created_at DESC);
