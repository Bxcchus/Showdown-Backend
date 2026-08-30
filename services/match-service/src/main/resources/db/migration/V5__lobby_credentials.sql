ALTER TABLE matches
    ADD COLUMN lobby_name VARCHAR(32),
    ADD COLUMN lobby_password_encrypted VARCHAR(160);

CREATE UNIQUE INDEX uq_active_lobby_name
    ON matches (lobby_name)
    WHERE lobby_name IS NOT NULL;

ALTER TABLE matches
    ADD CONSTRAINT ck_confirmed_lobby_credentials
        CHECK (
            status <> 'CONFIRMED'
            OR (lobby_name IS NOT NULL AND lobby_password_encrypted IS NOT NULL)
        );
