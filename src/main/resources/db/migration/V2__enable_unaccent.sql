-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Accent-insensitive name search: "maria" should find "María García", and "jose" should find "José"
-- (very common in Philippine names). The unaccent extension provides unaccent(text), which the user
-- search wraps around stored names so a diacritic-free query still matches an accented name.
-- Ships with the standard PostgreSQL contrib bundle (same as pg_trgm).
CREATE EXTENSION IF NOT EXISTS unaccent;
