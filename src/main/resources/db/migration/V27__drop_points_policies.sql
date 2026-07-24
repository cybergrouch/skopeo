-- V27: drop the obsolete global points policy (#525).
--
-- After #525 no event type awards from host-designated points bounded by a global policy — open play
-- is computed, tournaments are placement-based, and league awards nothing. The per-EventType
-- points_policies caps (min/max/validity, seeded in V16) are therefore dead: nothing reads them.
-- The per-club budget layer (club_point_budgets) and per-fixture designation are intentionally kept.

DROP TABLE IF EXISTS points_policies;
