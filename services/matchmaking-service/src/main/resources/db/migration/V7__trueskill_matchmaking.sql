ALTER TABLE queue_entries
    ADD COLUMN skill_mean DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN skill_deviation DOUBLE PRECISION NOT NULL DEFAULT 8.333333333333334,
    ADD CONSTRAINT ck_queue_skill_deviation_positive CHECK (skill_deviation > 0);

UPDATE queue_entries
SET skill_mean = 25.0 + (mmr - 1200) / 40.0;

ALTER TABLE player_rating_snapshots
    ADD COLUMN skill_mean DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN skill_deviation DOUBLE PRECISION NOT NULL DEFAULT 8.333333333333334,
    ADD CONSTRAINT ck_rating_snapshot_skill_deviation_positive CHECK (skill_deviation > 0);

UPDATE player_rating_snapshots
SET skill_mean = 25.0 + (rating - 1200) / 40.0;
