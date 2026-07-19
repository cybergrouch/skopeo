// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreatePlaceholderCommand
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Exercises the claim/merge re-point (#496) against a real PostgreSQL with the V23 migration applied,
 * covering the FK re-point and the UNIQUE-constraint dedupe on the membership tables.
 */
class PlaceholderMergeRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun realUser(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                    capabilities = setOf(element = Capability.PLAYER),
                ),
        )

    private fun placeholder(name: String): User =
        users.createPlaceholder(command = CreatePlaceholderCommand(displayName = name, sex = "Male"))

    @Test
    fun `createPlaceholder writes a login-less PLAYER-only row`() {
        val created = placeholder(name = "Dummy One")

        created.placeholder.shouldBeTrue()
        created.firebaseUid shouldBe null
        created.isActive.shouldBeTrue()
        created.capabilities shouldBe setOf(element = Capability.PLAYER)
        users.listPlaceholders().map { it.id } shouldContainExactlyInAnyOrder listOf(element = created.id)
    }

    @Test
    fun `claim re-points ratings and points, and dedupes shared team-users and event-participants`() {
        val claimant = realUser(uid = "claimant")
        val ph = placeholder(name = "Dummy")

        // A team both are on (dedupe expected) + a team only the placeholder is on (re-point expected).
        val sharedTeam = insertTeam(name = "shared")
        val phOnlyTeam = insertTeam(name = "phOnly")
        insertTeamUser(teamId = sharedTeam, userId = claimant.id)
        insertTeamUser(teamId = sharedTeam, userId = ph.id)
        insertTeamUser(teamId = phOnlyTeam, userId = ph.id)

        // An event both joined (dedupe) + an event only the placeholder joined (re-point).
        val sharedEvent = insertEvent(name = "sharedEvent")
        val phOnlyEvent = insertEvent(name = "phOnlyEvent")
        insertParticipant(eventId = sharedEvent, userId = claimant.id)
        insertParticipant(eventId = sharedEvent, userId = ph.id)
        insertParticipant(eventId = phOnlyEvent, userId = ph.id)

        // A rating-history row + a points-award row on the placeholder → must move to the claimant.
        insertRatingHistory(userId = ph.id)
        insertPointsAward(userId = ph.id)

        users.claimPlaceholder(placeholderId = ph.id, claimantId = claimant.id, claimedAt = LocalDateTime.now())

        // History + points re-pointed.
        users.hasRatingHistory(userId = claimant.id).shouldBeTrue()
        users.hasRatingHistory(userId = ph.id).shouldBeFalse()
        pointsAwardUserIds().shouldContainExactlyInAnyOrder(expected = listOf(element = claimant.id))

        // team_users: the shared team keeps ONE row (the claimant's — placeholder's deduped); the ph-only
        // team is re-pointed. The claimant now appears on both teams, once each.
        teamsFor(userId = claimant.id).shouldContainExactlyInAnyOrder(expected = listOf(sharedTeam, phOnlyTeam))
        teamsFor(userId = ph.id).shouldBe(expected = emptyList())

        // event_participants: same dedupe/re-point shape.
        eventsFor(userId = claimant.id).shouldContainExactlyInAnyOrder(expected = listOf(sharedEvent, phOnlyEvent))
        eventsFor(userId = ph.id).shouldBe(expected = emptyList())

        // Placeholder retired + linked.
        val retired = users.findById(id = ph.id).shouldBeRight()
        retired.isActive.shouldBeFalse()
        retired.canonicalUserId shouldBe claimant.id
        retired.claimedBy shouldBe claimant.id
    }

    // ---- direct-insert helpers (tables are internal to this package) ----

    private fun insertTeam(name: String): UUID =
        transaction {
            TeamsTable.insertAndGetId {
                it[TeamsTable.name] = name
                it[teamType] = "SINGLES"
            }.value
        }

    private fun insertTeamUser(
        teamId: UUID,
        userId: UUID,
    ) {
        transaction {
            TeamUsersTable.insert {
                it[TeamUsersTable.teamId] = teamId
                it[TeamUsersTable.userId] = userId
                it[position] = 1
            }
        }
    }

    private fun insertEvent(name: String): UUID =
        transaction {
            EventsTable.insertAndGetId {
                it[publicCode] = name.take(n = 6).uppercase().padEnd(length = 6, padChar = 'X')
                it[EventsTable.name] = name
                it[startDate] = java.time.LocalDate.now()
                it[endDate] = java.time.LocalDate.now()
                it[createdBy] = null
            }.value
        }

    private fun insertParticipant(
        eventId: UUID,
        userId: UUID,
    ) {
        transaction {
            EventParticipantsTable.insert {
                it[EventParticipantsTable.eventId] = eventId
                it[EventParticipantsTable.userId] = userId
                it[status] = "APPROVED"
            }
        }
    }

    private fun insertRatingHistory(userId: UUID) {
        transaction {
            UserRatingHistoryTable.insert {
                it[UserRatingHistoryTable.userId] = userId
                it[matchId] = null
                it[previousRating] = BigDecimal("3.000000")
                it[newRating] = BigDecimal("3.500000")
                it[ratingChange] = BigDecimal("0.500000")
                it[calculatedAt] = LocalDateTime.now()
            }
        }
    }

    private fun insertPointsAward(userId: UUID) {
        transaction {
            RankingPointAwardsTable.insert {
                it[RankingPointAwardsTable.userId] = userId
                it[points] = BigDecimal("10.0000")
                it[pointClass] = "TOURNAMENT"
                it[sourceType] = "MANUAL"
                it[band] = "3.5"
                it[sex] = "Male"
                it[validFrom] = LocalDateTime.now()
                it[validUntil] = LocalDateTime.now().plusYears(1)
                it[status] = "ACTIVE"
                it[awardedAt] = LocalDateTime.now()
            }
        }
    }

    private fun teamsFor(userId: UUID): List<UUID> =
        transaction {
            TeamUsersTable.selectAll().where { TeamUsersTable.userId eq userId }.map { it[TeamUsersTable.teamId].value }
        }

    private fun eventsFor(userId: UUID): List<UUID> =
        transaction {
            EventParticipantsTable
                .selectAll()
                .where { EventParticipantsTable.userId eq userId }
                .map { it[EventParticipantsTable.eventId].value }
        }

    private fun pointsAwardUserIds(): List<UUID> =
        transaction {
            RankingPointAwardsTable.selectAll().map { it[RankingPointAwardsTable.userId].value }
        }
}
