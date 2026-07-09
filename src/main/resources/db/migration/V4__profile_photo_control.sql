-- V4: user control over the profile photo (#303).
--
-- Until now the only photo was the one Google/Facebook supplied, re-synced on every login. Users
-- want to (a) hide it and (b) provide their own public image URL, and in either case the login-time
-- OAuth sync must stop overriding their choice.
--
-- Model: photo_url is repurposed as provider_photo_url (the OAuth photo, still synced on login and
-- retained so a user can revert). custom_photo_url is the user-set image; photo_hidden suppresses
-- display. The effective photo shown across the app is computed as:
--     photo_hidden ? NULL : COALESCE(custom_photo_url, provider_photo_url)
-- so hiding/customizing flows to every surface (public and private) without touching read sites.

ALTER TABLE users RENAME COLUMN photo_url TO provider_photo_url;

ALTER TABLE users ADD COLUMN custom_photo_url TEXT;
ALTER TABLE users ADD COLUMN photo_hidden BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.provider_photo_url IS 'OAuth (Google/Facebook) photo, synced on login (#219); retained even when a custom photo or hide is set (#303).';
COMMENT ON COLUMN users.custom_photo_url IS 'User-supplied public image URL; when set, shown instead of the OAuth photo and never overwritten by login sync (#303).';
COMMENT ON COLUMN users.photo_hidden IS 'When true, the profile photo is suppressed everywhere and login sync never re-enables it (#303).';
