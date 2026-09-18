// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The one rule deciding whether a team's stored name reaches a client (#1079).
 *
 * Worth its own test because the rule is easy to state and easy to get backwards: the column records
 * what a team is NOT (`is_temporary`), and the interesting case — an ad-hoc team WITH a plausible
 * name — looks correct until you know the name is a snapshot.
 */
class MatchSideOfficialNameTest {
    private fun side(
        name: String,
        isStanding: Boolean,
    ) = MatchSide(
        teamId = UUID.randomUUID(),
        userIds = listOf(element = UUID.randomUUID()),
        name = name,
        isStanding = isStanding,
    )

    @Test
    fun `a standing team's name is its own, so it is exposed (#1079)`() {
        side(name = "The Baseline Bandits", isStanding = true).officialName() shouldBe "The Baseline Bandits"
    }

    @Test
    fun `an ad-hoc fixture team's name is withheld even though it looks presentable (#1079)`() {
        // This is the case that matters. `teamName(users)` joins display names at creation, so "Ana/Bea"
        // reads fine and is a SNAPSHOT — it goes stale the moment Ana is renamed, while a client
        // deriving from the current roster stays correct. Exposing it would ship a plausible lie.
        side(name = "Ana/Bea", isStanding = false).officialName().shouldBeNull()
    }

    @Test
    fun `a standing team with a blank name falls back rather than showing nothing (#1079)`() {
        // `EventTeamService` auto-names when the host leaves it empty, so this should not occur — but an
        // empty string reaching a UI as a side label is worse than deriving one, so it is guarded.
        side(name = "", isStanding = true).officialName().shouldBeNull()
        side(name = "   ", isStanding = true).officialName().shouldBeNull()
    }
}
