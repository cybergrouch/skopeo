-- V66: a permanent replay of every live-scored match (#1145), and a marker for an edited result.
--
-- 1. match_replays — ONE JSON document per live-scored match: the effective events (the umpire's log
--    with undos resolved) and the per-point timeline derived from them by ScoreEngine. It exists because
--    live_match_events is working state (LIVE_MATCH.md §8a) that the #939 sweep deletes; the raw
--    keystrokes stay disposable, and this is the permanent record of how the match unfolded.
--
--    A separate table rather than a column on `matches`: every match load reads `matches` with all its
--    columns, and a replay (tens of KB) would ride along on queue, event and history reads that never
--    show one. Not a row per point: nothing queries a single point, replay and charts read a whole match.
--
--    `format_version` mirrors the document's own "version", so the server can find replays still on an
--    older format without opening them. Only one format is ever in use: an older one is regenerated from
--    its stored events when read (MatchReplayService).
--
-- 2. matches.result_edited_at — when a recorded result was edited after it was first saved (re-recorded
--    before rating, or corrected after it, #776). Null for a result recorded once. Public: the match page
--    flags an edited score to everyone, and a replay whose final score no longer matches is not shown.
--
-- Additive only: a new table, and a nullable column with no default, so no existing row has a
-- precondition to satisfy (docs/engineering/operations/DB_MIGRATIONS.md).
CREATE TABLE match_replays (
    match_id       UUID PRIMARY KEY,
    format_version INTEGER   NOT NULL,
    replay         JSONB     NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_match_replays_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT chk_match_replays_version CHECK (format_version >= 1)
);

ALTER TABLE matches ADD COLUMN result_edited_at TIMESTAMP;

COMMENT ON TABLE match_replays IS
    'Permanent per-match replay of a live-scored match (#1145): effective events plus a derived per-point '
    'timeline, as one versioned JSON document. The raw live_match_events log stays disposable (#939).';
COMMENT ON COLUMN matches.result_edited_at IS
    'When a recorded result was edited after it was first saved (#1145). Null for a result recorded once.';
