// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.TeamType

/**
 * The dedicated group classifier (#719): all "what group is this?" logic lives here, so the algorithm
 * stays a pure equality check. Singles derive from sex; doubles and mixed doubles collapse to one group.
 */
class GroupClassifierTest {
    private val classifier = GroupClassifier()

    @Test
    fun `singles derive the group from the player's sex`() {
        classifier.classify(sex = "Male", format = TeamType.SINGLES) shouldBe "Male"
        classifier.classify(sex = "Female", format = TeamType.SINGLES) shouldBe "Female"
    }

    @Test
    fun `a singles player with no sex has no group`() {
        classifier.classify(sex = null, format = TeamType.SINGLES) shouldBe null
        classifier.classify(sex = "", format = TeamType.SINGLES) shouldBe null
    }

    @Test
    fun `doubles and mixed doubles collapse to one shared group regardless of sex`() {
        val doubles = classifier.classify(sex = "Male", format = TeamType.DOUBLES)
        val mixed = classifier.classify(sex = "Female", format = TeamType.MIXED_DOUBLES)
        val doublesNoSex = classifier.classify(sex = null, format = TeamType.DOUBLES)

        doubles shouldBe GroupClassifier.DOUBLES_GROUP
        mixed shouldBe GroupClassifier.DOUBLES_GROUP
        doublesNoSex shouldBe GroupClassifier.DOUBLES_GROUP
    }
}
