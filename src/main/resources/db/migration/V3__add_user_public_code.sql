-- A short, human-readable, shareable player code (see issue #56). 6 chars from a Crockford-style
-- base32 alphabet (no I/L/O/U) so it's unambiguous to read aloud / type. The app generates codes
-- for new users; this migration backfills existing rows with unique random codes, then enforces
-- NOT NULL + uniqueness. Additive migration (baseline is V1; never edit an applied migration).
ALTER TABLE users ADD COLUMN public_code VARCHAR(6);

DO $$
DECLARE
    r RECORD;
    code TEXT;
    alphabet TEXT := '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; -- 32 chars, excludes I/L/O/U
BEGIN
    FOR r IN SELECT id FROM users WHERE public_code IS NULL LOOP
        LOOP
            code := '';
            FOR i IN 1..6 LOOP
                code := code || substr(alphabet, 1 + floor(random() * 32)::int, 1);
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM users WHERE public_code = code);
        END LOOP;
        UPDATE users SET public_code = code WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE users ALTER COLUMN public_code SET NOT NULL;
CREATE UNIQUE INDEX uq_users_public_code ON users (public_code);
