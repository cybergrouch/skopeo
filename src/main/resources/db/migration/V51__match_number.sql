-- V51: a human-facing match number, unique within its event (#898).
--
-- "Match #3" is what people say out loud during an event and write on a draw sheet, so this is an
-- IDENTIFIER, not a display ordinal: it belongs to the match, and display order derives from it rather
-- than the other way round. It never changes on its own — a status change (scheduled -> completed ->
-- rated) must leave every number in the event untouched. A deliberate host reorder does renumber, which
-- is a separate operation landing with the ordering switch.
--
-- Why a new column rather than reusing calc_sequence: that is the #331/#332 rating-calculation
-- tiebreaker. It is nullable by design ("null = not manually ordered"), written only by
-- MatchRepository.reorderCalcSequence, and therefore NULL for every match in any event nobody dragged.
-- Populating it densely would change how the rating pipeline orders un-dragged same-date matches, which
-- is reaching into a pipeline that writes ratings to solve a labelling problem.

ALTER TABLE matches ADD COLUMN match_number INTEGER;

-- Backfill before constraining, in this same file (DB_MIGRATIONS.md). Numbers follow the canonical order
-- that already exists — the same tuple listResultsByEvent/listAwaitingResults sort by — so the numbering
-- reproduces today's order exactly and nothing is reshuffled by this migration.
--
-- Disabled matches are numbered deliberately: a number said out loud during an event must never be
-- recycled, so a soft-deleted match keeps its number and leaves a gap rather than renumbering the rest.
-- event_id is NOT NULL as of V50, so every row falls into exactly one partition.
UPDATE matches m
SET match_number = s.rn
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY event_id
               ORDER BY match_date, calc_sequence NULLS LAST, completed_at, id
           ) AS rn
    FROM matches
) s
WHERE m.id = s.id;

ALTER TABLE matches ALTER COLUMN match_number SET NOT NULL;

ALTER TABLE matches ADD CONSTRAINT chk_matches_number_positive CHECK (match_number >= 1);

-- A plain unique index, not a partial one: because event_id is NOT NULL there is no "match with no event
-- to be #1 of" case to exclude. This is also what makes the number safe to quote — the database, not the
-- application, is what guarantees two matches in one event cannot share a number.
CREATE UNIQUE INDEX idx_matches_event_number ON matches (event_id, match_number);

COMMENT ON COLUMN matches.match_number IS
    'Human-facing 1-based match identifier within its event ("Match #3"), unique per event (#898). Never '
    'reassigned by a status change; a deliberate host reorder renumbers. Display order derives from this. '
    'Distinct from calc_sequence, which is the #331/#332 rating-calculation tiebreaker.';
