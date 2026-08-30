ALTER TABLE player_profiles
    ADD COLUMN riot_profile_icon_id INTEGER,
    ADD COLUMN riot_summoner_level BIGINT;

ALTER TABLE player_profiles
    ADD CONSTRAINT ck_player_riot_profile_icon_positive
        CHECK (riot_profile_icon_id IS NULL OR riot_profile_icon_id > 0),
    ADD CONSTRAINT ck_player_riot_level_positive
        CHECK (riot_summoner_level IS NULL OR riot_summoner_level > 0);
