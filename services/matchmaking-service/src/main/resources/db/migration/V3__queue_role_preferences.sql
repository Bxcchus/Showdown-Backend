ALTER TABLE queue_entries
    ADD COLUMN primary_role VARCHAR(16) NOT NULL DEFAULT 'MID',
    ADD COLUMN secondary_role VARCHAR(16) NOT NULL DEFAULT 'JUNGLE';

ALTER TABLE queue_entries
    ADD CONSTRAINT ck_queue_primary_role
        CHECK (primary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    ADD CONSTRAINT ck_queue_secondary_role
        CHECK (secondary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    ADD CONSTRAINT ck_queue_distinct_roles
        CHECK (primary_role <> secondary_role);
