// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

/**
 * The type of match by competitive context (issue #108) — open play, league (and its playoffs), or
 * tournament. Different contexts carry different pressure and so are more/less indicative of true skill;
 * each scales the calculated rating change by its [factor] (see how it folds into the algorithm's
 * `scale` term in docs/product/RATING_CALCULATION_ALGORITHM.md). Tournaments compress into 1–2 days
 * while leagues span a season (more open-play-like, hence a lower factor than tournaments but above
 * open play); league playoffs add further pressure.
 *
 * The former TOURNAMENT_INITIAL_ROUND / TOURNAMENT_PLAYOFFS split was collapsed into a single
 * [TOURNAMENT] (#560) — they shared a confidence weight class and the per-round distinction carried no
 * meaning; the surviving factor is the playoffs weight (1.2).
 *
 * The factor is the single tuning knob for this feature — kept here so it stays centralized.
 */
enum class MatchType(val factor: Double) {
    OPEN_PLAY(factor = 0.5),
    LEAGUE_PLAY(factor = 0.8),
    LEAGUE_PLAYOFFS(factor = 1.1),
    TOURNAMENT(factor = 1.2),
}
