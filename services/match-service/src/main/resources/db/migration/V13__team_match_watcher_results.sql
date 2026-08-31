CREATE TABLE team_match_watcher_results (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL,
    reporter_team VARCHAR(8) NOT NULL,
    winning_team VARCHAR(8) NOT NULL,
    game_id VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_team_match_watcher_reporter UNIQUE (match_id, reporter_id),
    CONSTRAINT ck_team_match_watcher_reporter_team CHECK (reporter_team IN ('BLUE', 'RED')),
    CONSTRAINT ck_team_match_watcher_winner CHECK (winning_team IN ('BLUE', 'RED'))
);

CREATE INDEX idx_team_match_watcher_consensus
    ON team_match_watcher_results (match_id, winning_team, reporter_team);
