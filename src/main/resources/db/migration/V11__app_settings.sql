-- V11: a generic app-settings key-value table backing the global UI theme (#378).
--
-- A single admin-controlled global setting: the UI theme. Stored generically as a (key, value) pair
-- so future global settings can reuse the same table. Publicly readable, admin-writable; each write
-- records the admin (updated_by) and time (updated_at) for the audit surface. The 'ui_theme' row is
-- seeded to 'AUTO' (the default) so a fresh install has a value; the app defaults to AUTO regardless.

CREATE TABLE app_settings (
    key        VARCHAR(64) PRIMARY KEY,
    value      VARCHAR(64) NOT NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMP   NOT NULL DEFAULT now()
);

INSERT INTO app_settings (key, value, updated_at) VALUES ('ui_theme', 'AUTO', now());
