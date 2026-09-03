// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase

/**
 * The append-only, versioned points-schedule store (#862).
 *
 * What is being protected here is not the plumbing but the guarantee: **an award's rates stay retrievable
 * forever.** Before versioning, editing a schedule overwrote the previous document, so an older award
 * became unexplainable — and since an open-play amount depends on the margin and band matchup read from
 * that schedule, unexplainable meant unauditable. Every test below is a way that could regress.
 */
class PointsConfigRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val configs = PointsConfigRepository()
    private val users = UserRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun admin(uid: String = "admin"): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                    ),
            ).toDomain()

    /** Every version row, oldest first, with whether it is the current one. */
    private fun versions(): List<Pair<Int, Boolean>> =
        transaction {
            exec(stmt = "SELECT version, is_current FROM points_schedule_versions ORDER BY version") { rs ->
                buildList { while (rs.next()) add(element = rs.getInt(1) to rs.getBoolean(2)) }
            }
        }.orEmpty()

    @Test
    fun `the migrated seed is version 1 and is current`() {
        configs.currentVersion() shouldBe 1
        versions() shouldBe listOf(element = 1 to true)
    }

    @Test
    fun `an edit appends a new version and moves the current pointer`() {
        val actor = admin()

        val row = configs.appendVersion(key = "open_play", value = """{"marker":"v2"}""", actorId = actor.id)

        row.value shouldBe """{"marker":"v2"}"""
        configs.currentVersion() shouldBe 2
        // v1 must still exist and must no longer be current — exactly one current, per the partial index.
        versions() shouldBe listOf(1 to false, 2 to true)
    }

    @Test
    fun `the previous version's document is left intact, which is the whole point`() {
        val actor = admin()
        val before = configs.get(key = "open_play").shouldNotBeNull().value

        configs.appendVersion(key = "open_play", value = """{"marker":"v2"}""", actorId = actor.id)

        // An award stamped v1 must still be able to find the rates it was paid under.
        configs.get(key = "open_play", version = 1).shouldNotBeNull().value shouldBe before
        configs.get(key = "open_play", version = 2).shouldNotBeNull().value shouldBe """{"marker":"v2"}"""
    }

    @Test
    fun `the untouched schedules are carried forward, so one global version spans all three`() {
        val actor = admin()
        val tournamentBefore = configs.get(key = "tournament").shouldNotBeNull().value
        val fullMatchBefore = configs.get(key = "full_match").shouldNotBeNull().value

        configs.appendVersion(key = "open_play", value = """{"marker":"v2"}""", actorId = actor.id)

        // Editing one table advances the version for all three; a version that held only the edited
        // document would leave the others unresolvable at that version.
        configs.get(key = "tournament", version = 2).shouldNotBeNull().value shouldBe tournamentBefore
        configs.get(key = "full_match", version = 2).shouldNotBeNull().value shouldBe fullMatchBefore
    }

    @Test
    fun `successive edits keep incrementing rather than reusing a version`() {
        val actor = admin()

        configs.appendVersion(key = "open_play", value = """{"marker":"v2"}""", actorId = actor.id)
        configs.appendVersion(key = "tournament", value = """{"marker":"v3"}""", actorId = actor.id)

        configs.currentVersion() shouldBe 3
        versions() shouldBe listOf(1 to false, 2 to false, 3 to true)
        // v3 carries v2's open-play edit forward, not the original seed.
        configs.get(key = "open_play", version = 3).shouldNotBeNull().value shouldBe """{"marker":"v2"}"""
    }

    @Test
    fun `an edit records who made it, while the seed has no author`() {
        val actor = admin()
        // The seed was written by a migration, so nobody owns it — that is how "never edited" is told
        // apart from "edited back to the defaults".
        configs.get(key = "open_play").shouldNotBeNull().updatedBy.shouldBeNull()

        val row = configs.appendVersion(key = "open_play", value = """{"marker":"v2"}""", actorId = actor.id)

        row.updatedBy shouldBe actor.id
        row.updatedAt.shouldNotBeNull()
    }

    @Test
    fun `an unknown key reads as null rather than throwing`() {
        configs.get(key = "no_such_schedule").shouldBeNull()
    }

    @Test
    fun `a version that was never created has no documents`() {
        configs.get(key = "open_play", version = 99).shouldBeNull()
    }

    @Test
    fun `a new key introduced by an edit does not disturb the carried-forward ones`() {
        val actor = admin()

        configs.appendVersion(key = "future_schedule", value = """{"marker":"new"}""", actorId = actor.id)

        // The three seeded keys travel to v2 alongside the new one.
        configs.get(key = "future_schedule", version = 2).shouldNotBeNull().value shouldBe """{"marker":"new"}"""
        configs.get(key = "open_play", version = 2).shouldNotBeNull().value shouldNotBe null
        configs.get(key = "tournament", version = 2).shouldNotBeNull()
        configs.get(key = "full_match", version = 2).shouldNotBeNull()
    }
}
