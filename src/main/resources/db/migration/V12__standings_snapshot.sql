-- V12: persist the standings leaderboard as published snapshots served paged (#220).
--
-- Today the standings are recomputed on every read and returned all at once. This makes them a
-- persisted, PUBLISHED snapshot served one (band, sex) page at a time. A snapshot is a generation
-- (keeping generations gives history and lets #146 stage a DRAFT before publishing); its entries are
-- the pre-ranked rows. The schema is deliberately source-agnostic: `ordering_value` is whatever the
-- ranking is sorted by (rating today; ranking points once #146 lands) so the read path never changes
-- when the source swaps. `status` supports PUBLISHED now and DRAFT later (#146). Tie-break (D8): higher
-- ordering_value, then earliest `achieved_at` — `tiebreak_rating`/`achieved_at` carry the tie-break
-- inputs so reads never recompute them.

CREATE TABLE standings_snapshots (
    id          UUID PRIMARY KEY,
    -- Monotonic insertion order: reads pick the latest PUBLISHED generation by `seq DESC`, which stays
    -- deterministic even when two rebuilds land in the same clock tick (computed_at truncates to micros).
    seq         BIGINT      GENERATED ALWAYS AS IDENTITY,
    computed_at TIMESTAMP   NOT NULL,
    as_of       DATE        NOT NULL,
    status      VARCHAR(16) NOT NULL
);

CREATE TABLE standings_entries (
    id              UUID PRIMARY KEY,
    snapshot_id     UUID          NOT NULL REFERENCES standings_snapshots(id) ON DELETE CASCADE,
    band            VARCHAR(8)    NOT NULL,
    sex             VARCHAR(16)   NOT NULL,
    rank            INT           NOT NULL,
    user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ordering_value  NUMERIC(6,4)  NOT NULL,
    tiebreak_rating NUMERIC(6,4),
    achieved_at     TIMESTAMP
);

-- The paged read: one (band, sex) group ordered by rank.
CREATE INDEX idx_standings_entries_page ON standings_entries (snapshot_id, band, sex, rank);
-- Jump-to-me: locate a user within a snapshot.
CREATE INDEX idx_standings_entries_user ON standings_entries (snapshot_id, user_id);
