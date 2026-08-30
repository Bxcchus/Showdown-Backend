CREATE TABLE queue_entries (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL UNIQUE,
    region VARCHAR(16) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    reservation_id UUID,
    CONSTRAINT ck_queue_status CHECK (status IN ('QUEUED', 'RESERVED')),
    CONSTRAINT ck_queue_reservation CHECK (
        (status = 'QUEUED' AND reservation_id IS NULL)
        OR (status = 'RESERVED' AND reservation_id IS NOT NULL)
    )
);

CREATE INDEX idx_queue_selection
    ON queue_entries (region, mode, status, joined_at, id)
    WHERE status = 'QUEUED';

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at, id)
    WHERE published_at IS NULL;
