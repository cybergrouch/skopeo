-- V10: a shareable public code on clubs (#327).
--
-- Clubs get a stable, shareable public code and a public-by-code page, mirroring users/events/matches
-- (#56/#136/#138). The column is added nullable, backfilled with generated 6-char Crockford-base32
-- codes for existing rows, then made NOT NULL + uniquely indexed — the same shape as the other
-- public_code columns (see V1). New rows get their code from the application on insert.

ALTER TABLE clubs ADD COLUMN public_code VARCHAR(6);

-- Backfill existing clubs with a unique 6-char code from the same alphabet the app uses
-- (0-9 A-Z minus I/L/O/U). A per-row loop retries until the generated code is unique.
DO $$
DECLARE
    club RECORD;
    candidate TEXT;
    alphabet TEXT := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
BEGIN
    FOR club IN SELECT id FROM clubs WHERE public_code IS NULL LOOP
        LOOP
            candidate := '';
            FOR i IN 1..6 LOOP
                candidate := candidate || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM clubs WHERE public_code = candidate);
        END LOOP;
        UPDATE clubs SET public_code = candidate WHERE id = club.id;
    END LOOP;
END $$;

ALTER TABLE clubs ALTER COLUMN public_code SET NOT NULL;

CREATE UNIQUE INDEX uq_clubs_public_code ON clubs (public_code);

COMMENT ON COLUMN clubs.public_code IS 'Stable shareable code (#327), mirroring users.public_code';
