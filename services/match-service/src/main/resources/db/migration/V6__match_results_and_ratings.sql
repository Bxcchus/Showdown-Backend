ALTER TABLE matches
    ADD COLUMN winning_team VARCHAR(8),
    ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE matches
    ADD CONSTRAINT ck_match_winning_team CHECK (winning_team IS NULL OR winning_team IN ('BLUE', 'RED'));

CREATE INDEX idx_match_history ON matches (completed_at DESC) WHERE winning_team IS NOT NULL;

CREATE TABLE player_ratings (
    player_id UUID PRIMARY KEY,
    rating INTEGER NOT NULL DEFAULT 1200,
    peak_rating INTEGER NOT NULL DEFAULT 1200,
    games INTEGER NOT NULL DEFAULT 0,
    wins INTEGER NOT NULL DEFAULT 0,
    losses INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_player_rating_non_negative CHECK (rating >= 0 AND peak_rating >= rating),
    CONSTRAINT ck_player_rating_games CHECK (games = wins + losses)
);

CREATE TABLE rating_changes (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    previous_rating INTEGER NOT NULL,
    previous_peak_rating INTEGER NOT NULL,
    rating_delta INTEGER NOT NULL,
    new_rating INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_match_player_rating_change UNIQUE (match_id, player_id),
    CONSTRAINT ck_rating_change_non_negative CHECK (previous_rating >= 0 AND new_rating >= 0)
);

CREATE INDEX idx_rating_change_history ON rating_changes (player_id, created_at DESC);
