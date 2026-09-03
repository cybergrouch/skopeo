// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.FullMatchPointsConfig
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.testsupport.PostgresTestDatabase

private val JSON = Json { ignoreUnknownKeys = true }

private const val REGENERATE_HINT =
    "The Kotlin defaults in common/contract/PointsConfigContract.kt no longer match what the migrations " +
        "seed. Changing a schedule in code is not enough (#862): the database is authoritative, so a code-only " +
        "change has NO runtime effect. Add a migration that inserts the next points_schedule_versions row, " +
        "seeds its three documents from the new defaults, and moves is_current."

/**
 * The guard that makes "a schedule change needs a version bump" enforced rather than remembered (#862).
 *
 * The Kotlin defaults are the readable definition of the schedule and the seed source; the database is what
 * awarding actually reads. Those two can drift in one direction only — someone edits
 * `PointsConfigContract` and does not add a seeding migration — and the result is a **silent no-op**: the
 * new rates never apply, because nothing consults the code at award time any more.
 *
 * Silent-and-inert is a far better failure than silent-and-wrong (which is what shipped before versioning:
 * new rates recorded under the old version number). But inert is still a confusing afternoon, so this
 * asserts the two agree in a freshly migrated database and names the migration to write when they do not.
 *
 * Note this is a **fresh-database** invariant. In a live environment an admin edit legitimately moves the
 * current version away from the defaults; that is the point of versioning. The container here has only ever
 * been migrated, so what it holds is exactly what the migrations seed.
 *
 * Sibling of [MigrationChecksumManifestTest] (#854) — same reasoning, different artifact: move the
 * detection to commit time, because the runtime check happens too late and somewhere else.
 */
class PointsScheduleSeedTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun setUp() {
            PostgresTestDatabase.start()
        }
    }

    @Test
    fun `the seeded open-play schedule matches the Kotlin defaults`() {
        seeded(key = "open_play", deserializer = OpenPlayPointsConfig.serializer()) shouldBe OpenPlayPointsConfig.DEFAULT
    }

    @Test
    fun `the seeded tournament schedule matches the Kotlin defaults`() {
        seeded(key = "tournament", deserializer = TournamentPointsConfig.serializer()) shouldBe TournamentPointsConfig.DEFAULT
    }

    @Test
    fun `the seeded Full Match window matches the Kotlin defaults`() {
        seeded(key = "full_match", deserializer = FullMatchPointsConfig.serializer()) shouldBe FullMatchPointsConfig.DEFAULT
    }

    @Test
    fun `exactly one schedule version is current`() {
        // uq_points_schedule_current enforces this, so a failure here means the index is gone rather than
        // that some code path misbehaved — which is worth telling apart.
        currentVersionCount() shouldBe 1
    }

    /** The document stored for [key] under the current version, decoded. */
    private fun <T> seeded(
        key: String,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val raw =
            transaction {
                exec(
                    stmt =
                        """
                        SELECT c.value
                          FROM points_config c
                          JOIN points_schedule_versions v ON v.version = c.version
                         WHERE v.is_current AND c.key = '$key'
                        """.trimIndent(),
                ) { rs -> if (rs.next()) rs.getString(1) else null }
            }
        if (raw == null) {
            fail(msg = "No seeded '$key' schedule for the current version. $REGENERATE_HINT")
        }
        return runCatching { JSON.decodeFromString(deserializer = deserializer, string = raw) }
            .getOrElse { fail(msg = "The seeded '$key' schedule does not parse: ${it.message}. $REGENERATE_HINT") }
    }

    private fun currentVersionCount(): Int =
        transaction {
            exec(stmt = "SELECT count(*) FROM points_schedule_versions WHERE is_current") { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        } ?: 0
}
