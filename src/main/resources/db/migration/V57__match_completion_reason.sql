-- V57: how a match ended, not just that it did (#911).
--
-- `matches.status` says SCHEDULED / IN_PROGRESS / COMPLETED / CANCELLED. It cannot say that a COMPLETED
-- match ended because somebody retired or was defaulted, and LiveMatch needs that distinction for three
-- different reasons — the scoreline reads "1-3 (ret)", the rating pipeline treats the two differently,
-- and a disputed result needs to show why it stopped.
--
-- ONE COLUMN, NOT TWO. The design note (LIVE_MATCH.md §10) called for a completion reason AND a record
-- of which player retired or defaulted. The second is derivable and therefore deliberately not stored: a
-- player who retires always LOSES, so the conceding side is by definition the one that is not the
-- designated winner_team_id. Storing it as well would add a way for the two to disagree — a row saying
-- team1 conceded and also won — with nothing able to adjudicate. The live event log already names the
-- conceding side (ScoreEvent.Retired(side)) and the engine turns it into a win for the opponent, so the
-- record derives it back the same way it was written.
--
-- RATING TREATMENT, decided in §10 and enforced in RatingCalculationService rather than here:
--   RETIRED   -> rated on the real score. There was tennis; dominance is computable from it.
--   DEFAULTED -> not rated at all. A no-show has no scoreline, and rating one would invent a
--                performance nobody gave.
-- Ranking points follow the RECORD in both cases (the opponent won), which is the existing behaviour of
-- EventFinalizeAwarder and needs no change.
--
-- A WIDENING with a safe default: every existing row is a match that either has not finished or finished
-- normally, and COMPLETED is exactly that claim. Nothing to backfill and nothing that can fail on
-- production data.
ALTER TABLE matches ADD COLUMN completion_reason VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';

ALTER TABLE matches ADD CONSTRAINT chk_matches_completion_reason CHECK (completion_reason IN
    ('COMPLETED', 'RETIRED', 'DEFAULTED'));

-- A match that did not end normally must name a winner: with no scoreline to derive one from, an
-- un-designated retirement would be a result nobody can read. NOT VALID is deliberate — the constraint
-- guards new writes immediately, and there is nothing to validate against history because every existing
-- row is COMPLETED by the default above. A later VALIDATE would be a no-op, so it is not scheduled.
ALTER TABLE matches ADD CONSTRAINT chk_matches_abnormal_end_has_winner CHECK (
    completion_reason = 'COMPLETED' OR winner_team_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN matches.completion_reason IS
    'How the match ended (#911): COMPLETED, RETIRED or DEFAULTED. The conceding side is NOT stored — it '
    'is the side that is not winner_team_id, because a player who retires always loses.';
