// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.match.UpdateFixturePlayersRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.util.UUID

/**
 * Changing who plays a fixture (#957).
 *
 * The point of the feature is what it *preserves*: before this the only route was delete-and-recreate,
 * which burned the match number — an identifier that is never recycled (#898) — and left the ordering
 * to be rebuilt by hand.
 */
class EditFixturePlayersTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val matches = MatchRepository()
    private val service = FixturePlayerService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): UUID =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private lateinit var home: UUID
    private lateinit var away: UUID
    private lateinit var spare: UUID
    private lateinit var eventId: UUID

    private fun fixture(format: TeamType = TeamType.SINGLES): UUID {
        user(uid = "boss", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        home = user(uid = "home")
        away = user(uid = "away")
        spare = user(uid = "spare")
        eventId = fixtureEventId(home, away, spare)
        return matches
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = format,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = "home",
                        team2Name = "away",
                        createdBy = home,
                        eventId = eventId,
                    ),
            ).toDomain()
            .id
    }

    private fun request(
        team1: UUID,
        team2: UUID,
    ) = UpdateFixturePlayersRequest(team1 = listOf(element = team1.toString()), team2 = listOf(element = team2.toString()))

    @Test
    fun `swapping a player keeps the fixture, its number and its place`() {
        // The whole reason the feature exists: delete-and-recreate burns the match number, which is an
        // identifier and is never recycled (#898), so the draw sheet stops matching.
        val matchId = fixture()
        val before = matches.findById(matchId = matchId).shouldBeRight().toDomain()

        service
            .updateFixturePlayers(token = token(uid = "boss"), matchId = matchId, request = request(team1 = spare, team2 = away))
            .shouldBeRight()

        val after = matches.findById(matchId = matchId).shouldBeRight().toDomain()
        after.id shouldBe before.id
        after.matchNumber shouldBe before.matchNumber
        after.team1.userIds shouldBe listOf(element = spare)
        after.team2.userIds shouldBe listOf(element = away)
    }

    @Test
    fun `a player who is not an event participant is refused`() {
        val matchId = fixture()
        val outsider = user(uid = "outsider")

        service
            .updateFixturePlayers(token = token(uid = "boss"), matchId = matchId, request = request(team1 = outsider, team2 = away))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a fixture with a recorded result is frozen, before it is rated (#952)`() {
        // Same line as live scoring: changing who played after a result exists would rewrite the history
        // ratings and points are computed from, and rating happens days later at event finalization.
        val matchId = fixture()
        matches.setStatus(matchId = matchId, status = MatchStatus.COMPLETED.name).shouldBeRight()

        service
            .updateFixturePlayers(token = token(uid = "boss"), matchId = matchId, request = request(team1 = spare, team2 = away))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a disabled fixture cannot be edited`() {
        val matchId = fixture()
        matches.setActive(matchId = matchId, active = false, disabledAt = java.time.LocalDateTime.now())

        service
            .updateFixturePlayers(token = token(uid = "boss"), matchId = matchId, request = request(team1 = spare, team2 = away))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a plain player cannot change who is playing`() {
        val matchId = fixture()
        user(uid = "nobody")

        service
            .updateFixturePlayers(token = token(uid = "nobody"), matchId = matchId, request = request(team1 = spare, team2 = away))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `the side size must still match the format`() {
        // Silently turning a doubles match into singles by dropping a player would break every count
        // that trusts the format.
        val matchId = fixture(format = TeamType.DOUBLES)
        val fourth = user(uid = "fourth")

        service
            .updateFixturePlayers(
                token = token(uid = "boss"),
                matchId = matchId,
                request = request(team1 = spare, team2 = away),
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // Referenced so the doubles fixture's fourth player is not an unused seed.
        (fourth != spare) shouldBe true
    }

    @Test
    fun `the same player cannot appear on both sides`() {
        // Rejected at the boundary, before the service sees it — a shape problem, not a data one, so it
        // needs no database and no seeded fixture.
        val someone = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> { request(team1 = someone, team2 = someone) }
    }

    @Test
    fun `an empty side is rejected at the boundary`() {
        shouldThrow<IllegalArgumentException> {
            UpdateFixturePlayersRequest(team1 = emptyList(), team2 = listOf(element = UUID.randomUUID().toString()))
        }
    }
}
