// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.CreatePlaceholderCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.MatchUmpireEntity
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Who scored a match, as the public surfaces name them (#1183): the final scorer from the durable umpire
 * credit, and public codes resolved by the player display rules.
 */
class MatchScorersTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val live = LiveMatchRepository()
    private val scorers = MatchScorers()
    private val start: LocalDateTime = LocalDateTime.of(2026, 6, 1, 9, 0)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(uid: String): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    ),
            ).toDomain()

    private fun matchId(): UUID {
        val home = user(uid = "home").id
        val away = user(uid = "away").id
        return MatchRepository()
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.of(2026, 6, 1),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = "home",
                        team2Name = "away",
                        createdBy = home,
                        eventId = fixtureEventId(home, away),
                    ),
            ).toDomain()
            .id
    }

    private fun credit(
        user: User,
        events: Int,
        lastMinute: Long,
    ) = MatchUmpireEntity(
        userId = user.id,
        eventsRecorded = events,
        firstRecordedAt = start,
        lastRecordedAt = start.plusMinutes(lastMinute),
    )

    @Test
    fun `the final scorer is whoever acted last, not whoever acted most`() {
        val match = matchId()
        val opener = user(uid = "opener")
        val closer = user(uid = "closer")
        live.recordUmpires(
            matchId = match,
            umpires = listOf(credit(user = opener, events = 90, lastMinute = 40), credit(user = closer, events = 3, lastMinute = 55)),
        )

        val scorer = scorers.finalScorer(matchId = match).shouldNotBeNull()

        scorer.displayName shouldBe "closer"
        scorer.publicCode shouldBe closer.publicCode
        scorer.isDeleted shouldBe false
    }

    @Test
    fun `a match not scored live has no final scorer`() {
        scorers.finalScorer(matchId = matchId()).shouldBeNull()
    }

    @Test
    fun `a deleted final scorer is flagged and never named`() {
        val match = matchId()
        val gone = user(uid = "gone")
        live.recordUmpires(matchId = match, umpires = listOf(element = credit(user = gone, events = 5, lastMinute = 10)))
        users.deactivate(id = gone.id).shouldBeRight()

        val scorer = scorers.finalScorer(matchId = match).shouldNotBeNull()

        scorer.displayName.shouldBeNull()
        scorer.isDeleted shouldBe true
        scorer.publicCode shouldBe gone.publicCode
    }

    @Test
    fun `codes resolve by the player rules, and an unknown code is left out`() {
        val named = user(uid = "named")
        val placeholder =
            users
                .createPlaceholder(command = CreatePlaceholderCommand(displayName = "Stand In", sex = "Male"))
                .toDomain()

        val resolved = scorers.resolve(codes = listOf(named.publicCode, placeholder.publicCode, named.publicCode, "NOPE00"))

        resolved.keys shouldBe setOf(named.publicCode, placeholder.publicCode)
        resolved.getValue(key = named.publicCode).displayName shouldBe "named"
        resolved.getValue(key = placeholder.publicCode).isPlaceholder shouldBe true
        scorers.publicCodesOf(userIds = listOf(element = named.id)) shouldBe mapOf(named.id to named.publicCode)
    }
}
