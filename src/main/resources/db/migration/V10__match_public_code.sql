-- Give each match a stable, shareable public code (issue #136), mirroring users.public_code.
-- Added nullable first so any existing rows can be backfilled, then made NOT NULL + unique.

ALTER TABLE matches ADD COLUMN public_code VARCHAR(6);

-- Backfill existing matches with a random 6-char Crockford-base32 code (no I/L/O/U), one per row.
UPDATE matches SET public_code = sub.code
FROM (
    SELECT id,
           string_agg(substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ', 1 + floor(random() * 32)::int, 1), '') AS code
    FROM matches, generate_series(1, 6)
    GROUP BY id
) sub
WHERE matches.id = sub.id;

ALTER TABLE matches ALTER COLUMN public_code SET NOT NULL;

CREATE UNIQUE INDEX uq_matches_public_code ON matches (public_code);
