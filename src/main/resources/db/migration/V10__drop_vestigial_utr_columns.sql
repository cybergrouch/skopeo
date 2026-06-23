-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- NTRP-only: drop the dead UTR columns left over from the removed UTR support.
-- These columns were never mapped by the Exposed schema after the rating-system
-- removal (V9) and carry no data path; remove them so the schema matches the code.
ALTER TABLE user_ratings DROP COLUMN utr_rating;
ALTER TABLE user_ratings DROP COLUMN utr_last_synced;
