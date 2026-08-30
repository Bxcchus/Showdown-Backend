ALTER TABLE queue_entries
    ADD COLUMN mmr INTEGER NOT NULL DEFAULT 1200,
    ADD CONSTRAINT ck_queue_mmr_non_negative CHECK (mmr >= 0);

CREATE INDEX idx_queue_mmr_selection
    ON queue_entries (region, mode, status, mmr, joined_at)
    WHERE status = 'QUEUED';

CREATE TABLE player_rating_snapshots (
    player_id UUID PRIMARY KEY,
    rating INTEGER NOT NULL,
    peak_rating INTEGER NOT NULL,
    games INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rating_snapshot_values CHECK (
        rating >= 0 AND peak_rating >= rating AND games >= 1
    )
);
