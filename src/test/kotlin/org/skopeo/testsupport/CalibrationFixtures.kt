// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.repository.UserRatingsTable

/**
 * Clear every player's calibration stamp, i.e. make them all **settled** (#881).
 *
 * Call this in a fixture that assigns ratings and then expects ordinary awarding. `setRating` **is** a
 * manual designation, so a freshly-rated player is in calibration and earns no ranking points for their
 * first N rated matches — which is correct behaviour that silently empties any test asserting awards.
 *
 * Settled is the state of every player who predates the feature and of anyone past their window, so it is
 * the right default for a test about awarding, reversal or un-finalize rather than about calibration.
 *
 * Tests that are *about* calibration must not use this — they set the stamps they need explicitly.
 */
fun settleAllRatings() {
    transaction {
        UserRatingsTable.update {
            it[calibrationStartedAt] = null
        }
    }
}
