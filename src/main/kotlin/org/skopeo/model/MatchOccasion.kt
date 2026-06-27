// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

/**
 * The competitive context a match was played in (issue #108). Different occasions carry different
 * pressure and so are more/less indicative of true skill; each scales the calculated rating change
 * by its [factor]. Tournaments compress into 1–2 days while leagues span a season (more open-play-like,
 * hence a lower factor than tournaments but above open play); playoffs add further pressure within each.
 *
 * The factor is the single tuning knob for this feature — kept here so it stays centralized.
 */
enum class MatchOccasion(val factor: Double) {
    OPEN_PLAY(factor = 0.5),
    LEAGUE_PLAY(factor = 0.8),
    TOURNAMENT_INITIAL_ROUND(factor = 1.0),
    LEAGUE_PLAYOFFS(factor = 1.1),
    TOURNAMENT_PLAYOFFS(factor = 1.2),
}
