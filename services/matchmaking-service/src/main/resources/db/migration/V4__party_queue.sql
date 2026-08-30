ALTER TABLE queue_entries ADD COLUMN party_id UUID;

CREATE INDEX idx_queue_party
    ON queue_entries (party_id)
    WHERE party_id IS NOT NULL;
