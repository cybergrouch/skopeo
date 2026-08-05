// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

/**
 * The type of match by competitive context (issue #108) — open play or tournament. Different contexts
 * carry different pressure and so are more/less indicative of true skill; each scales the calculated
 * rating change by its [factor] (see how it folds into the algorithm's `scale` term in
 * docs/product/RATING_CALCULATION_ALGORITHM.md). Tournaments compress into 1–2 days (higher pressure,
 * higher factor) while open play is casual (lower factor).
 *
 * The former TOURNAMENT_INITIAL_ROUND / TOURNAMENT_PLAYOFFS split was collapsed into a single
 * [TOURNAMENT] (#560) — they shared a confidence weight class and the per-round distinction carried no
 * meaning; the surviving factor is the playoffs weight (1.2). The former LEAGUE_PLAY / LEAGUE_PLAYOFFS
 * types were removed (#669) so match type aligns 1:1 with [EventType]; existing rows reclassify to
 * OPEN_PLAY.
 *
 * The factor is the single tuning knob for this feature — kept here so it stays centralized.
 */
enum class MatchType(val factor: Double) {
    OPEN_PLAY(factor = 0.5),
    TOURNAMENT(factor = 1.2),
}
