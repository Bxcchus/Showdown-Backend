ALTER TABLE duel_challenges DROP CONSTRAINT ck_duel_challenge_status;
ALTER TABLE duel_challenges ADD CONSTRAINT ck_duel_challenge_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'COMPLETED', 'DECLINED', 'CANCELLED', 'EXPIRED'));

UPDATE duel_challenges challenge
SET status = 'COMPLETED', responded_at = COALESCE(match.completed_at, challenge.responded_at)
FROM matches match
WHERE challenge.match_id = match.id
  AND challenge.status = 'ACCEPTED'
  AND match.status = 'COMPLETED';
