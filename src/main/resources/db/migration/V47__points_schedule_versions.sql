-- V47: version the points schedules, and tag every award with the version that produced it (#862).
--
-- THE PROBLEM. points_config was keyed by `key` alone and written with an upsert, so editing a schedule
-- OVERWROTE the previous one. Since #525/#836 an open-play amount depends on the margin and the band
-- matchup read from that schedule, so once it had been edited there was no way to know what an older award
-- was actually paid under -- and no way to explain it. That is data loss on every schedule edit, whether or
-- not anyone ever asks.
--
-- THE SHAPE. Schedules become append-only and versioned. A version row says "this set of schedules
-- existed"; the three documents hang off it; an award records the version that produced it. A schedule
-- change inserts a NEW version and moves the current pointer -- it never rewrites a document.
--
-- ONE GLOBAL VERSION spans open play, Full Match and tournament. Editing only the tournament table bumps
-- the version for open-play awards whose rates did not change, which is harmless: the version identifies
-- WHICH DOCUMENT SET APPLIED, not what changed. In exchange there is one number to reason about instead of
-- three that can drift.
--
-- APPEND-ONLY, PRECISELY. The DATA is append-only: no points_config row is ever updated or deleted. The
-- current-version POINTER does mutate -- flipping is_current is an update. That is unavoidable in any
-- "which one applies now" design; the guarantee that matters is that no historical rate is ever altered.
--
-- WHY is_current AND NOT is_active. Elsewhere in this schema is_active means "not soft-deleted" and many
-- rows are active at once (users, matches, events, clubs). A schedule needs the opposite property: exactly
-- one is current. Borrowing the name would import the wrong reading, and someone would eventually mark two
-- versions active and wonder which one pays. The partial unique index below makes "exactly one" a fact of
-- the schema rather than an invariant the service has to remember.

CREATE TABLE points_schedule_versions (
    version    INTEGER   PRIMARY KEY,
    is_current BOOLEAN   NOT NULL DEFAULT FALSE,
    created_by UUID      REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE points_schedule_versions IS
    'One row per points-schedule version (#862). Append-only; exactly one row has is_current.';
COMMENT ON COLUMN points_schedule_versions.is_current IS
    'The version new awards are computed under. Exactly one, enforced by uq_points_schedule_current. NOT a soft-delete flag.';

-- Exactly one current version. Indexing on a constant expression makes the uniqueness global rather than
-- per-key, which is what "one current version across all three schedules" means.
CREATE UNIQUE INDEX uq_points_schedule_current
    ON points_schedule_versions ((TRUE))
    WHERE is_current;

-- v1 = the schedules as they stand in code today. created_by is null: nobody chose this, it is the
-- shipped default (#525 kept the schedule as code defaults deliberately).
INSERT INTO points_schedule_versions (version, is_current) VALUES (1, TRUE);

-- ---------------------------------------------------------------------------------------------------
-- points_config: primary key becomes (version, key), so the destructive upsert cannot be written again.
-- ---------------------------------------------------------------------------------------------------

ALTER TABLE points_config ADD COLUMN version INTEGER;

-- Backfill BEFORE constraining (docs/engineering/operations/DB_MIGRATIONS.md). Any row already present is
-- what production/dev was serving, so it IS v1 -- whether or not an admin had edited it.
UPDATE points_config SET version = 1 WHERE version IS NULL;

ALTER TABLE points_config ALTER COLUMN version SET NOT NULL;
ALTER TABLE points_config DROP CONSTRAINT points_config_pkey;
ALTER TABLE points_config ADD PRIMARY KEY (version, key);
ALTER TABLE points_config
    ADD CONSTRAINT points_config_version_fkey
    FOREIGN KEY (version) REFERENCES points_schedule_versions (version);

-- Seed v1's three documents from the Kotlin defaults in common/contract/PointsConfigContract.kt.
--
-- These are seeded here, in the migration, rather than fetched from code at award time: making the
-- database authoritative is what turns a forgotten version bump from SILENT MIS-VERSIONING (new rates
-- recorded under an old version) into an INERT no-op (the code change simply does not apply). The Kotlin
-- defaults remain the readable definition and the seed source -- see the KDoc there -- and
-- PointsScheduleSeedTest asserts a freshly migrated database matches them, so the two cannot drift.
--
-- DO NOTHING, not DO UPDATE: a row already present is an admin's own edit, and v1 is defined as "whatever
-- was being served before versioning existed".
INSERT INTO points_config (version, key, value, updated_at)
VALUES (1, 'open_play', '{"maxMargin":6,"rows":[{"relation":"EQUAL","margin":1,"winnerPoints":5,"loserPoints":0},{"relation":"FAVORITE","margin":1,"winnerPoints":2,"loserPoints":1},{"relation":"UPSET","margin":1,"winnerPoints":7,"loserPoints":-2},{"relation":"EQUAL","margin":2,"winnerPoints":8,"loserPoints":0},{"relation":"FAVORITE","margin":2,"winnerPoints":2,"loserPoints":1},{"relation":"UPSET","margin":2,"winnerPoints":10,"loserPoints":-2},{"relation":"EQUAL","margin":3,"winnerPoints":13,"loserPoints":0},{"relation":"FAVORITE","margin":3,"winnerPoints":2,"loserPoints":0},{"relation":"UPSET","margin":3,"winnerPoints":15,"loserPoints":-2},{"relation":"EQUAL","margin":4,"winnerPoints":21,"loserPoints":0},{"relation":"FAVORITE","margin":4,"winnerPoints":2,"loserPoints":0},{"relation":"UPSET","margin":4,"winnerPoints":23,"loserPoints":-2},{"relation":"EQUAL","margin":5,"winnerPoints":34,"loserPoints":0},{"relation":"FAVORITE","margin":5,"winnerPoints":2,"loserPoints":0},{"relation":"UPSET","margin":5,"winnerPoints":36,"loserPoints":-2},{"relation":"EQUAL","margin":6,"winnerPoints":55,"loserPoints":0},{"relation":"FAVORITE","margin":6,"winnerPoints":2,"loserPoints":0},{"relation":"UPSET","margin":6,"winnerPoints":57,"loserPoints":-2}],"validityDays":91}', CURRENT_TIMESTAMP)
ON CONFLICT (version, key) DO NOTHING;

INSERT INTO points_config (version, key, value, updated_at)
VALUES (1, 'tournament', '{"sanctioned":[1000,800,600,500],"unsanctioned":[400,300,200,100],"validityDays":365}', CURRENT_TIMESTAMP)
ON CONFLICT (version, key) DO NOTHING;

INSERT INTO points_config (version, key, value, updated_at)
VALUES (1, 'full_match', '{"validityDays":182}', CURRENT_TIMESTAMP)
ON CONFLICT (version, key) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------
-- ranking_point_awards: the version that produced the award, plus the two band inputs.
-- ---------------------------------------------------------------------------------------------------

ALTER TABLE ranking_point_awards ADD COLUMN points_schedule_version INTEGER;

-- Every existing award predates versioning and was therefore paid under the pre-versioning schedule,
-- which is exactly what v1 records. Backfill before constraining.
UPDATE ranking_point_awards SET points_schedule_version = 1 WHERE points_schedule_version IS NULL;

ALTER TABLE ranking_point_awards ALTER COLUMN points_schedule_version SET NOT NULL;
ALTER TABLE ranking_point_awards
    ADD CONSTRAINT ranking_point_awards_schedule_version_fkey
    FOREIGN KEY (points_schedule_version) REFERENCES points_schedule_versions (version);

-- The two band strings the calculator actually consumed.
--
-- OpenPlayPointsCalculator.compute(band1, band2, ...) compares exactly these, so persisting them makes a
-- derivation reproducible BY CONSTRUCTION. The alternative -- reading them back off the match's other
-- award rows -- works for singles and is SILENTLY WRONG for doubles, because teamBand averages the
-- members' RAW ratings and then bands the mean, while `band` on each row is that player's own banded
-- value. Banding-the-mean is not meaning-the-bands.
--
-- Deliberately NOT derivable from user_rating_history either: that is the band at MATCH time, while the
-- awarder uses findCurrentRatings at FINALIZE time. Using it would produce a different relation than the
-- one that was paid.
--
-- Nullable: a placement award has no band relation and a manual/EXTERNAL grant has no match. Null means
-- "no derivation recorded", which the UI states rather than guessing at.
ALTER TABLE ranking_point_awards ADD COLUMN team_band VARCHAR(8);
ALTER TABLE ranking_point_awards ADD COLUMN opponent_band VARCHAR(8);

COMMENT ON COLUMN ranking_point_awards.points_schedule_version IS
    'The points-schedule version this award was computed under (#862). Existing awards backfilled to v1.';
COMMENT ON COLUMN ranking_point_awards.team_band IS
    'The awarding side''s band as fed to the calculator (#862); null for placement and manual grants.';
COMMENT ON COLUMN ranking_point_awards.opponent_band IS
    'The opposing side''s band as fed to the calculator (#862); null for placement and manual grants.';
