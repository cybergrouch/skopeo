-- V55: the live scoring log and the scorer claim (#911, step 3).
--
-- Two tables, for two different jobs.
--
-- 1. live_match_events — the append-only stack the umpire builds and ScoreEngine folds into a score.
--    It is WORKING STATE, not a second record of results (LIVE_MATCH.md §8a): finalize translates it
--    through the existing uploadResult, and matches/match_sets stay the system of record. Nothing here
--    is ever updated or deleted; an undo APPENDS a row pointing at the sequence it cancels, because the
--    stated goal is to audit everything and popping is the one operation that defeats that.
--
-- 2. live_match_scorers — who is currently keying a match in, so a second umpire can see it and take
--    over deliberately rather than by accident.

CREATE TABLE live_match_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id        UUID   NOT NULL,
    sequence        BIGINT NOT NULL,
    kind            VARCHAR(24) NOT NULL,
    -- Which side an action belongs to. NEVER "left"/"right": switching ends in the umpire view is a
    -- display preference and is deliberately not recorded, so a log written in screen positions would be
    -- corrupted by a flip (§6). Null for the kinds that name no side.
    side            VARCHAR(8),
    -- SERVER_ASSIGNED only. A player, not a side — in doubles the serve rotates through four people, and
    -- this is the single place the model has to know that.
    player_id       UUID,
    -- UNDONE only: the sequence this row cancels. Deliberately NOT a foreign key to live_match_events.id
    -- — it points at a (match_id, sequence) pair, and a marker aimed at a sequence that was never written
    -- must be inert rather than rejected, which is what "undo when there is nothing to undo" means.
    target_sequence BIGINT,
    recorded_by     UUID   NOT NULL,
    recorded_at     TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_live_match_events_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_live_match_events_recorded_by FOREIGN KEY (recorded_by) REFERENCES users(id) ON DELETE RESTRICT,

    -- THE concurrency guard. Cloud Run runs --min-instances=1 --max-instances=2, so two umpire writes can
    -- land on different instances at the same moment; choosing Firestore for the broadcast removed the
    -- fan-out problem but not this one (§2). Application-side ordering cannot fix it — only the database
    -- can. A writer computes the next sequence from the log it read and inserts; the loser of a race gets
    -- a unique violation and retries against the log that actually won. Nothing interleaves, nothing is
    -- lost. Same shape match_number uses (#898).
    CONSTRAINT uq_live_match_events_sequence UNIQUE (match_id, sequence),
    CONSTRAINT chk_live_match_events_sequence_positive CHECK (sequence >= 1),

    -- Each kind carries exactly the payload it needs and no other, so a malformed row cannot reach the
    -- engine. Enforced here rather than only in Kotlin because this table is append-only working state
    -- that a later sweep and an audit summary both read.
    CONSTRAINT chk_live_match_events_kind CHECK (kind IN (
        'POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'TIEBREAK_STARTED',
        'SERVER_ASSIGNED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED', 'UNDONE')),
    CONSTRAINT chk_live_match_events_side CHECK (side IS NULL OR side IN ('TEAM1', 'TEAM2')),
    CONSTRAINT chk_live_match_events_payload CHECK (
        (kind = 'UNDONE'          AND target_sequence IS NOT NULL AND side IS NULL     AND player_id IS NULL) OR
        (kind = 'SERVER_ASSIGNED' AND player_id       IS NOT NULL AND side IS NULL     AND target_sequence IS NULL) OR
        (kind = 'TIEBREAK_STARTED' AND side IS NULL AND player_id IS NULL AND target_sequence IS NULL) OR
        (kind IN ('POINT_WON', 'GAME_AWARDED', 'SET_AWARDED', 'RETIRED', 'DEFAULTED', 'MATCH_AWARDED')
             AND side IS NOT NULL AND player_id IS NULL AND target_sequence IS NULL))
);

-- The only read pattern that matters: the whole log for one match, in sequence order, on every write.
-- No snapshotting (§7) — a match is a few hundred rows, so this index is the entire performance story.
CREATE INDEX idx_live_match_events_match ON live_match_events(match_id, sequence);

COMMENT ON TABLE live_match_events IS
    'Append-only umpire action log for a live match (#911). Working state, not a record of results — '
    'finalize translates it through uploadResult. Never updated or deleted; undo appends a marker.';

-- Who is keying a match in right now. One row per match at most, so the PK is the match itself.
--
-- Deliberately a SOFT claim, not a lock: the unique constraint above already makes interleaved or lost
-- writes impossible, so this exists to stop two umpires confusing each other, not to protect the data.
-- Any authorized scorer may take over, which is recorded by overwriting the row. That matters courtside —
-- a phone that dies mid-match must not strand the fixture behind a lock waiting for a timeout nobody
-- chose well.
CREATE TABLE live_match_scorers (
    match_id   UUID PRIMARY KEY,
    scorer_id  UUID      NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_live_match_scorers_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_live_match_scorers_scorer FOREIGN KEY (scorer_id) REFERENCES users(id) ON DELETE RESTRICT
);

COMMENT ON TABLE live_match_scorers IS
    'Soft claim on a live match (#911) — who is scoring it now, so a second umpire takes over '
    'deliberately. Not a lock: uq_live_match_events_sequence is what protects the data.';

-- Who umpired, kept on the MATCH rather than only on the log.
--
-- Every live_match_events row already carries recorded_by, so the log knows who did what. But §8a makes
-- the log disposable once the match is recorded — so relying on it would mean the umpire's name is
-- attributed right up until the moment the evidence is swept away, and a match page rendered a year later
-- could not say who scored it. This is the durable half: written at finalize by folding the log, and
-- deliberately outliving it.
--
-- A row per (match, umpire) rather than a single column, because a takeover is expected (see
-- live_match_scorers) and a match scored by two people should credit both. The counts and timestamps are
-- what distinguish "umpired the match" from "tapped one point during a handover".
CREATE TABLE match_umpires (
    match_id          UUID      NOT NULL,
    user_id           UUID      NOT NULL,
    events_recorded   INTEGER   NOT NULL,
    first_recorded_at TIMESTAMP NOT NULL,
    last_recorded_at  TIMESTAMP NOT NULL,

    CONSTRAINT pk_match_umpires PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_match_umpires_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_match_umpires_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_match_umpires_events_positive CHECK (events_recorded >= 1),
    CONSTRAINT chk_match_umpires_window CHECK (last_recorded_at >= first_recorded_at)
);

CREATE INDEX idx_match_umpires_user ON match_umpires(user_id);

COMMENT ON TABLE match_umpires IS
    'Who scored a live match (#911), folded from the event log at finalize. Outlives the log on purpose: '
    'live_match_events is disposable working state, this is the permanent credit.';
