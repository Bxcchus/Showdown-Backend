ALTER TABLE match_players
    ADD COLUMN assigned_role VARCHAR(16) NOT NULL DEFAULT 'MID';

ALTER TABLE match_players
    ADD CONSTRAINT ck_match_assigned_role
        CHECK (assigned_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT'));

ALTER TABLE matches DROP CONSTRAINT ck_match_status;
ALTER TABLE matches
    ADD CONSTRAINT ck_match_status
        CHECK (status IN ('READY_CHECK', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'COMPLETED'));

-- Les confirmations créées avant l'existence du lobby web ne doivent pas
-- réapparaître comme des lobbies actifs avec le rôle MID par défaut.
UPDATE matches
SET status = 'COMPLETED'
WHERE status = 'CONFIRMED';
