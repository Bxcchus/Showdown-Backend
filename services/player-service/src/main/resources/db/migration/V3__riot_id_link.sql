ALTER TABLE player_profiles
    ADD COLUMN riot_game_name VARCHAR(16),
    ADD COLUMN riot_tag_line VARCHAR(5),
    ADD COLUMN riot_linked_at TIMESTAMPTZ;

ALTER TABLE player_profiles
    ADD CONSTRAINT ck_player_riot_id_complete CHECK (
        (riot_game_name IS NULL AND riot_tag_line IS NULL AND riot_linked_at IS NULL)
        OR (riot_game_name IS NOT NULL AND riot_tag_line IS NOT NULL AND riot_linked_at IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_player_riot_id_ci
    ON player_profiles (lower(riot_game_name), upper(riot_tag_line))
    WHERE riot_game_name IS NOT NULL;
