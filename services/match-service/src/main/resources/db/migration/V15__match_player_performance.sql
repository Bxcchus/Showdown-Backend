ALTER TABLE match_players
    ADD COLUMN kills INTEGER,
    ADD COLUMN deaths INTEGER,
    ADD COLUMN assists INTEGER,
    ADD COLUMN item_ids VARCHAR(96);
