-- V31: collapse the two tournament match types into a single TOURNAMENT (#560).
--
-- TOURNAMENT_INITIAL_ROUND and TOURNAMENT_PLAYOFFS shared a confidence weight class and carried no
-- meaningful per-round distinction, so they are merged into one MatchType. The surviving rating factor
-- is the playoffs weight (1.2), applied in code; existing rows of either variant map to TOURNAMENT.

UPDATE matches
SET match_type = 'TOURNAMENT'
WHERE match_type IN ('TOURNAMENT_INITIAL_ROUND', 'TOURNAMENT_PLAYOFFS');
