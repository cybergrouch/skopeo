// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.CreateEventTeamCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventTeamRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedClub
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Fixture creation from durable event-team refs (#720): resolution, slot order, and size validation. */
class MatchFixtureTeamRefTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingAssembler()
    private val matchRepo = MatchRepository()
    private val events = EventRepository()
    private val eventTeams = EventTeamRepository()
    private val service = MatchService(matches = matchRepo, ratings = ratings, users = users, events = events, eventTeams = eventTeams)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun rated(uid: String): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = setOf(element = Capability.PLAYER),
                    ),
            ).toDomain()
        ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0")
        return user
    }

    private fun host(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = setOf(Capability.PLAYER, Capability.HOST),
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun doublesEvent(
        hostId: UUID,
        participants: List<UUID>,
    ): UUID =
        events
            .create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Doubles Cup",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = participants,
                        createdBy = hostId,
                        format = TeamType.DOUBLES,
                    ),
            ).toDomain()
            .id

    private fun singlesEvent(
        hostId: UUID,
        participants: List<UUID>,
    ): UUID =
        events
            .create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Singles Cup",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = participants,
                        createdBy = hostId,
                        format = TeamType.SINGLES,
                    ),
            ).toDomain()
            .id

    private fun team(
        eventId: UUID,
        name: String,
        members: List<UUID>,
    ): UUID = eventTeams.create(command = CreateEventTeamCommand(eventId = eventId, name = name, memberUserIds = members)).team.id

    @Test
    fun `a doubles fixture resolves both sides from team refs in slot order`() {
        val h = host(uid = "host")
        val p1 = rated(uid = "p1")
        val p2 = rated(uid = "p2")
        val p3 = rated(uid = "p3")
        val p4 = rated(uid = "p4")
        val eventId = doublesEvent(hostId = h.id, participants = listOf(p1.id, p2.id, p3.id, p4.id))
        val t1 = team(eventId = eventId, name = "A", members = listOf(p1.id, p2.id))
        val t2 = team(eventId = eventId, name = "B", members = listOf(p3.id, p4.id))

        val match =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.DOUBLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1 = emptyList(),
                            team2 = emptyList(),
                            team1Ref = t1,
                            team2Ref = t2,
                            eventId = eventId,
                        ),
                ).shouldBeRight()

        match.team1.userIds shouldBe listOf(p1.id.toString(), p2.id.toString())
        match.team2.userIds shouldBe listOf(p3.id.toString(), p4.id.toString())
    }

    @Test
    fun `a doubles team cannot fill a singles fixture side`() {
        val h = host(uid = "host")
        val p1 = rated(uid = "p1")
        val p2 = rated(uid = "p2")
        val p3 = rated(uid = "p3")
        val eventId = doublesEvent(hostId = h.id, participants = listOf(p1.id, p2.id, p3.id))
        val doublesTeam = team(eventId = eventId, name = "A", members = listOf(p1.id, p2.id))

        val error =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1 = emptyList(),
                            team2 = listOf(element = p3.id),
                            team1Ref = doublesTeam,
                            team2Ref = null,
                            eventId = eventId,
                        ),
                ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "SINGLES fixture needs 1"
    }

    @Test
    fun `a fixture rejects a player appearing on both sides via a team ref`() {
        val h = host(uid = "host")
        val p1 = rated(uid = "p1")
        val eventId = singlesEvent(hostId = h.id, participants = listOf(element = p1.id))
        val t1 = team(eventId = eventId, name = "A", members = listOf(element = p1.id))

        val error =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1 = emptyList(),
                            // The same player, raw, on the other side → a duplicate across the match.
                            team2 = listOf(element = p1.id),
                            team1Ref = t1,
                            team2Ref = null,
                            eventId = eventId,
                        ),
                ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "more than once in a match"
    }

    @Test
    fun `a fixture rejects an unknown team ref`() {
        val h = host(uid = "host")
        val p1 = rated(uid = "p1")
        val p2 = rated(uid = "p2")
        val eventId = singlesEvent(hostId = h.id, participants = listOf(p1.id, p2.id))

        val error =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1 = emptyList(),
                            team2 = listOf(element = p2.id),
                            team1Ref = UUID.randomUUID(),
                            team2Ref = null,
                            eventId = eventId,
                        ),
                ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "not found"
    }

    @Test
    fun `a team ref requires an event`() {
        val h = host(uid = "host")
        val p1 = rated(uid = "p1")
        val p2 = rated(uid = "p2")
        val eventId = doublesEvent(hostId = h.id, participants = listOf(p1.id, p2.id))
        val t1 = team(eventId = eventId, name = "A", members = listOf(p1.id, p2.id))

        val error =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.DOUBLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1 = emptyList(),
                            team2 = emptyList(),
                            team1Ref = t1,
                            team2Ref = t1,
                            eventId = null,
                        ),
                ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "requires an event"
    }
}
