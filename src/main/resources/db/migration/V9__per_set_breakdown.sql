-- SPDX-FileCopyrightText: 2026 Lange Pantoja
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Per-set calculation breakdown (#110): the v2 calculator records one step per set. Stored as
-- nullable JSON; null/empty for v1, initial assessments, and rows committed before #110.
ALTER TABLE user_rating_history
    ADD COLUMN set_breakdown TEXT;
