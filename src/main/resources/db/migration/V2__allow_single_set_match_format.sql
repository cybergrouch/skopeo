-- Allow the SINGLE_SET match format (see issue #54). Additive migration: the schema baseline
-- is the consolidated V1, so we extend the CHECK constraint rather than editing V1 (editing a
-- previously-applied migration would fail Flyway validation on existing databases).
ALTER TABLE matches DROP CONSTRAINT chk_match_format;
ALTER TABLE matches ADD CONSTRAINT chk_match_format
    CHECK (match_format IN ('BEST_OF_THREE', 'BEST_OF_FIVE', 'SINGLE_SET'));
