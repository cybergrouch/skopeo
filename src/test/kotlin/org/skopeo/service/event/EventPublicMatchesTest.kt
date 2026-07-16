// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The public event page (#361) lists its match fixtures read-only, each carrying the
 * Scheduled / Awaiting rating / Rated status derivation. Kept in its own class so
 * EventServiceTest stays under the LargeClass threshold.
 */
class EventPublicMatchesTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val matchRepo = MatchRepository()
    private val service = EventService(events = events, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun input(participants: List<UUID>) =
        CreateEventInput(
            name = "Spring Open",
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(7),
            participantIds = participants,
        )

    private fun seedFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
    ): Match =
        matchRepo.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.parse("2026-03-02"),
                    team1UserIds = listOf(element = p1.id),
                    team2UserIds = listOf(element = p2.id),
                    team1Name = "p1",
                    team2Name = "p2",
                    createdBy = host.id,
                    eventId = eventId,
                ),
        )

    private fun recordResult(
        match: Match,
        recordedBy: UUID,
    ) {
        matchRepo.addResult(
            matchId = match.id,
            sets =
                listOf(
                    element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId),
                ),
            winnerTeamId = match.team1.teamId,
            recordedBy = recordedBy,
            completedAt = LocalDateTime.now(),
        )
    }

    @Test
    fun `publicByCode carries each match's status - empty, scheduled, awaiting rating, and rated (#361)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val created = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight()

        fun publicMatches() = service.publicByCode(token = null, code = created.event.publicCode).shouldBeRight().matches

        // An event with no fixtures reports an empty match list.
        publicMatches().shouldHaveSize(size = 0)

        // A plain fixture (Scheduled), a recorded-but-unrated one (Awaiting rating), and a rated one.
        val scheduled = seedFixture(eventId = created.event.id, host = host, p1 = p1, p2 = p2)
        val awaiting =
            seedFixture(eventId = created.event.id, host = host, p1 = p1, p2 = p2).also {
                recordResult(match = it, recordedBy = host.id)
            }
        val rated =
            seedFixture(
                eventId = created.event.id,
                host = host,
                p1 = p1,
                p2 = p2,
            ).also { recordResult(match = it, recordedBy = host.id) }
        matchRepo.markRated(matchId = rated.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        // status → rated, keyed by public code: SCHEDULED/unrated, COMPLETED/unrated, COMPLETED/rated.
        val byCode = publicMatches().associate { it.publicCode to (it.status to it.rated) }
        byCode shouldBe
            mapOf(
                scheduled.publicCode to ("SCHEDULED" to false),
                awaiting.publicCode to ("COMPLETED" to false),
                rated.publicCode to ("COMPLETED" to true),
            )
    }
}
