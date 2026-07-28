-- V32: per-admin "preview as non-admin" preference for raw NTRP rating visibility (#583).
--
-- Raw NTRP values (full precision) are shown to ADMINISTRATORs only. This per-user flag lets an
-- administrator preview the non-admin experience on LIVE (band + confidence + speedometer only, no
-- raw value, band-jump-only rating history, no calculation breakdown) without affecting anyone else.
-- Only meaningful for administrators; default FALSE = an admin sees raw values normally.

ALTER TABLE users
    ADD COLUMN preview_ratings_as_non_admin BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.preview_ratings_as_non_admin IS
    'Per-admin preview toggle (#583): when true, this admin sees the non-admin (band-only) view of ratings.';
