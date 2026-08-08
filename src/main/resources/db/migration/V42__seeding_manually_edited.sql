-- V42: track whether a seeding's order was set by hand (#718).
--
-- A host can now drag-reorder a generated seeding and Save it (PUT /seeding); the saved order bypasses
-- the deterministic sort and reassigns seeds 1..N by position. This flag records that a seeding's order
-- is a manual override so the UI can warn before a Regenerate (POST) discards it. Regenerating resets
-- the flag to false. Existing rows are all generated (never hand-edited), so the default is false.

ALTER TABLE seedings
    ADD COLUMN manually_edited BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN seedings.manually_edited IS 'True when the host reordered and saved this seeding by hand (#718); reset to false on regenerate';
