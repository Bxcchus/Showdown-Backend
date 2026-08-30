ALTER TABLE player_ratings
    ADD COLUMN skill_mean DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN skill_deviation DOUBLE PRECISION NOT NULL DEFAULT 8.333333333333334,
    ADD CONSTRAINT ck_player_rating_skill_deviation_positive CHECK (skill_deviation > 0);

UPDATE player_ratings
SET skill_mean = 25.0 + (rating - 1200) / 40.0;

ALTER TABLE rating_changes
    ADD COLUMN previous_skill_mean DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN previous_skill_deviation DOUBLE PRECISION NOT NULL DEFAULT 8.333333333333334,
    ADD COLUMN new_skill_mean DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN new_skill_deviation DOUBLE PRECISION NOT NULL DEFAULT 8.333333333333334,
    ADD CONSTRAINT ck_rating_change_previous_deviation_positive CHECK (previous_skill_deviation > 0),
    ADD CONSTRAINT ck_rating_change_new_deviation_positive CHECK (new_skill_deviation > 0);

UPDATE rating_changes
SET previous_skill_mean = 25.0 + (previous_rating - 1200) / 40.0,
    new_skill_mean = 25.0 + (new_rating - 1200) / 40.0;
