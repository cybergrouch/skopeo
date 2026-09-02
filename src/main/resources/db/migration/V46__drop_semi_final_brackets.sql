-- V46: retire the semi-final placement brackets (#837).
--
-- PlacementBracket narrows to CHAMPIONSHIP_FINALS and PLATE_FINALS: a placing is awarded only where a
-- fixture actually decided it. SEMI_FINALS_WITH_PLATE paid nothing directly (its losers were paid by the
-- Plate Finals), and SEMI_FINALS_NO_PLATE awarded "3rd" to two players and 4th to nobody. A semi-final is
-- now an ordinary non-placement fixture and earns the open-play per-set schedule (#836).
--
-- Nulling the bracket is exactly the new semantics for these rows: no bracket = not a placement fixture,
-- so they fall into the per-set half of a tournament's payout. Also clears is_placement_match so the two
-- columns stay consistent (the awarder requires both).
--
-- There is no CHECK constraint on placement_bracket (V26 created it as a plain VARCHAR, V29 widened it),
-- so nothing needs narrowing here — this backfill exists purely so no row is left holding a value the
-- Kotlin enum can no longer deserialise. Production has no tournament events; dev/staging may.
UPDATE matches
   SET placement_bracket = NULL,
       is_placement_match = FALSE
 WHERE placement_bracket IN ('SEMI_FINALS_NO_PLATE', 'SEMI_FINALS_WITH_PLATE');
