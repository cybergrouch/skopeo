-- V68: the editable AUTO theme schedule (#1196).
--
-- AUTO used to resolve from a table hard-coded in the web app (SEASON_WINDOWS in web/src/lib/season.ts).
-- It now lives in app_settings under 'ui_theme_schedule', next to 'ui_theme', as one ordered JSON list of
-- { name, theme, start: { month, day }, end: { month, day } } rows: inclusive, repeating every year, tested
-- top to bottom with the first match winning, and uncovered dates showing Skopeo OG.
--
-- 1. Widen app_settings.value from VARCHAR(64) to TEXT, because the schedule does not fit in 64 characters.
--    Widening is not a tightening: every existing value already fits, so there is nothing to backfill.
-- 2. Seed the schedule with the hard-coded table it replaces (including #1194's Skopeo OG window), turned
--    into inclusive ranges with no gaps. ThemeScheduleRules.DEFAULT is the same list, and
--    ThemeScheduleRulesTest fails the build if the two drift apart. ON CONFLICT keeps an existing row, so the
--    migration is safe to run against a database where the key was somehow written already.

ALTER TABLE app_settings ALTER COLUMN value TYPE TEXT;

INSERT INTO app_settings (key, value, updated_at) VALUES ('ui_theme_schedule', '[
{"name":"Australian Open","theme":"AO","start":{"month":1,"day":1},"end":{"month":1,"day":31}},
{"name":"Valentine''s Day","theme":"VALENTINES","start":{"month":2,"day":1},"end":{"month":2,"day":14}},
{"name":"Spring","theme":"SPRING","start":{"month":2,"day":15},"end":{"month":3,"day":31}},
{"name":"Skopeo OG","theme":"SKOPEO_OG","start":{"month":4,"day":1},"end":{"month":4,"day":15}},
{"name":"Clay","theme":"CLAY","start":{"month":4,"day":16},"end":{"month":6,"day":10}},
{"name":"Grass","theme":"GRASS","start":{"month":6,"day":11},"end":{"month":7,"day":31}},
{"name":"US Open","theme":"US_OPEN","start":{"month":8,"day":1},"end":{"month":9,"day":15}},
{"name":"Rainy","theme":"RAINY","start":{"month":9,"day":16},"end":{"month":10,"day":16}},
{"name":"Off-Season","theme":"OFF_SEASON","start":{"month":10,"day":17},"end":{"month":10,"day":24}},
{"name":"Halloween","theme":"HALLOWEEN","start":{"month":10,"day":25},"end":{"month":11,"day":2}},
{"name":"Autumn","theme":"AUTUMN","start":{"month":11,"day":3},"end":{"month":12,"day":9}},
{"name":"Christmas","theme":"CHRISTMAS","start":{"month":12,"day":10},"end":{"month":12,"day":31}}
]', now())
ON CONFLICT (key) DO NOTHING;
