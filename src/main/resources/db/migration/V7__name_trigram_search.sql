-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Typo-tolerant name search for GET /api/v1/users?name=.
--
-- pg_trgm provides trigram similarity (so "Alyce" finds "Alice"); the GIN index
-- on lower(value) accelerates both the ILIKE substring match and the similarity()
-- lookups the user search runs across every name (all types) on a profile.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_user_names_value_trgm
    ON user_names USING gin (lower(value) gin_trgm_ops);
