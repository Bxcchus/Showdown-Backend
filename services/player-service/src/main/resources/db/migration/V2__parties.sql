CREATE TABLE parties (
    id UUID PRIMARY KEY,
    leader_id UUID NOT NULL,
    region VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_party_region CHECK (region IN ('EUW', 'EUNE', 'NA'))
);

CREATE TABLE party_members (
    id UUID PRIMARY KEY,
    party_id UUID NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    player_id UUID NOT NULL UNIQUE,
    display_name VARCHAR(24) NOT NULL,
    primary_role VARCHAR(16) NOT NULL,
    secondary_role VARCHAR(16) NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    simulated BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_party_member_primary_role CHECK (primary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    CONSTRAINT ck_party_member_secondary_role CHECK (secondary_role IN ('TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT')),
    CONSTRAINT ck_party_member_distinct_roles CHECK (primary_role <> secondary_role)
);

CREATE INDEX idx_party_members_party ON party_members (party_id, joined_at, id);

CREATE TABLE party_invitations (
    id UUID PRIMARY KEY,
    party_id UUID NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    inviter_id UUID NOT NULL,
    invitee_id UUID NOT NULL,
    invitee_display_name VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    CONSTRAINT ck_party_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED'))
);

CREATE INDEX idx_party_invitations_invitee
    ON party_invitations (invitee_id, status, created_at DESC);
CREATE UNIQUE INDEX uk_party_pending_invitee
    ON party_invitations (party_id, invitee_id)
    WHERE status = 'PENDING';
