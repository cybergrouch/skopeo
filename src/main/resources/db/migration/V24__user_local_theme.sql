-- V24: per-user "local theme" (#514).
--
-- A profile owner may pick their own UI theme, overriding the global one (#378). Two nullable
-- columns on users: `local_theme` (a ThemeSetting name; NULL = follow the global theme, the default
-- for everyone) and `local_theme_set_at` (when they last picked it). The effective theme is computed
-- client-side: local NULL → global; local set + fixed global → local wins; local set + global AUTO →
-- the current season's start is compared against local_theme_set_at, so each new season re-applies
-- the seasonal look, and re-setting local reclaims the choice until the next season boundary. Stateless
-- — no scheduled/batch clearing. See docs/product/UI_SEASONAL_THEMING.md.

ALTER TABLE users ADD COLUMN local_theme VARCHAR(32);
ALTER TABLE users ADD COLUMN local_theme_set_at TIMESTAMP;

COMMENT ON COLUMN users.local_theme IS
  'The profile owner''s chosen UI theme (#514): a ThemeSetting name overriding the global theme. NULL (the default) means follow the global theme.';
COMMENT ON COLUMN users.local_theme_set_at IS
  'When the user last set local_theme (#514); compared against the current season start when the global theme is AUTO, so a new season re-applies the seasonal look. NULL when local_theme is unset.';
