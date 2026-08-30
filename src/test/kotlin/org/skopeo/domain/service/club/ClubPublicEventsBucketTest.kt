// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.club

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.EventBucket
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The SQL bucket predicates behind the paginated club-page listing (#786).
 *
 * These deliberately mirror the cases the web's `eventBuckets.ts` tests cover. Paginating per bucket meant
 * the Upcoming / Unfinalized / Finalized rules (#483) now exist on BOTH sides — the server for this
 * endpoint, the client for the Event Organizer, which loads every event anyway to group by club. This suite
 * is what stops the two drifting: if a rule changes here and not there (or vice versa), one of these fails.
 */
class ClubPublicEventsBucketTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clubs = ClubRepository()
    private val events = EventRepository()
    private val matchRepo = MatchRepository()
    private val reader = ClubPublicReader(clubs = clubs, events = events, matches = matchRepo)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private val today: LocalDate = LocalDate.now()

    private fun provision(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = setOf(element = Capability.PLAYER),
                ),
        ).toDomain()

    /** A club plus a player to populate rosters with. */
    private data class Fixture(
        val clubId: UUID,
        val publicCode: String,
        val host: User,
        val player: User,
    )

    private fun fixture(): Fixture {
        val host = provision(uid = "host")
        val player = provision(uid = "p1")
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id)).toDomain()
        return Fixture(clubId = club.id, publicCode = club.publicCode, host = host, player = player)
    }

    /** Create a club event named [name] over the given dates. Returns its id. */
    private fun event(
        f: Fixture,
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): UUID =
        events
            .create(
                command =
                    CreateEventCommand(
                        name = name,
                        startDate = startDate,
                        endDate = endDate,
                        participantIds = listOf(element = f.player.id),
                        createdBy = f.host.id,
                        clubId = f.clubId,
                    ),
            ).toDomain()
            .id

    /** Record a COMPLETED result on [eventId] — the "activity started" signal for the Unfinalized bucket. */
    private fun recordResult(
        f: Fixture,
        eventId: UUID,
    ) {
        val opponent = provision(uid = "opp-$eventId")
        val match =
            matchRepo
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = today,
                            team1UserIds = listOf(element = f.player.id),
                            team2UserIds = listOf(element = opponent.id),
                            team1Name = "t1",
                            team2Name = "t2",
                            createdBy = f.host.id,
                            eventId = eventId,
                        ),
                ).toDomain()
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = f.host.id,
            completedAt = LocalDateTime.now(),
        )
    }

    private fun namesIn(
        f: Fixture,
        bucket: EventBucket,
        limit: Int = 10,
        offset: Int = 0,
    ): List<String> =
        reader
            .publicEventsByCode(code = f.publicCode, bucket = bucket.name, limit = limit, offset = offset)
            .shouldBeRight()
            .items
            .map { it.name }

    @Test
    fun `a future untouched event is Upcoming (#786)`() {
        val f = fixture()
        event(f = f, name = "Next Month", startDate = today.plusDays(30), endDate = today.plusDays(31))

        namesIn(f = f, bucket = EventBucket.UPCOMING) shouldBe listOf(element = "Next Month")
        namesIn(f = f, bucket = EventBucket.UNFINALIZED).shouldBeEmpty()
        namesIn(f = f, bucket = EventBucket.FINALIZED).shouldBeEmpty()
    }

    @Test
    fun `an event ending today is still Upcoming, not Unfinalized (#786)`() {
        val f = fixture()
        event(f = f, name = "Running Now", startDate = today.minusDays(1), endDate = today)

        // The boundary is `endDate >= today`, so today's event has not "ended".
        namesIn(f = f, bucket = EventBucket.UPCOMING) shouldBe listOf(element = "Running Now")
        namesIn(f = f, bucket = EventBucket.UNFINALIZED).shouldBeEmpty()
    }

    @Test
    fun `an ended event is Unfinalized (#786)`() {
        val f = fixture()
        event(f = f, name = "Last Week", startDate = today.minusDays(7), endDate = today.minusDays(1))

        namesIn(f = f, bucket = EventBucket.UNFINALIZED) shouldBe listOf(element = "Last Week")
        namesIn(f = f, bucket = EventBucket.UPCOMING).shouldBeEmpty()
    }

    @Test
    fun `a future event with a recorded result is Unfinalized, not Upcoming (#786)`() {
        val f = fixture()
        val id = event(f = f, name = "Started Early", startDate = today.plusDays(1), endDate = today.plusDays(10))
        recordResult(f = f, eventId = id)

        // Activity started but not concluded — results outweigh a future end date.
        namesIn(f = f, bucket = EventBucket.UNFINALIZED) shouldBe listOf(element = "Started Early")
        namesIn(f = f, bucket = EventBucket.UPCOMING).shouldBeEmpty()
    }

    @Test
    fun `a finalized event is Finalized even with a future end date and no results (#786)`() {
        val f = fixture()
        val id = event(f = f, name = "Closed Early", startDate = today.plusDays(1), endDate = today.plusDays(10))
        events.finalize(id = id, finalizedBy = f.host.id, finalizedAt = LocalDateTime.now())

        // Finalized wins over everything.
        namesIn(f = f, bucket = EventBucket.FINALIZED) shouldBe listOf(element = "Closed Early")
        namesIn(f = f, bucket = EventBucket.UPCOMING).shouldBeEmpty()
        namesIn(f = f, bucket = EventBucket.UNFINALIZED).shouldBeEmpty()
    }

    @Test
    fun `each bucket sorts the way the Event Organizer does (#483, #786)`() {
        val f = fixture()
        // Upcoming: start date ascending.
        event(f = f, name = "Later", startDate = today.plusDays(20), endDate = today.plusDays(21))
        event(f = f, name = "Sooner", startDate = today.plusDays(2), endDate = today.plusDays(3))
        // Unfinalized: end date descending.
        event(f = f, name = "Ended Long Ago", startDate = today.minusDays(30), endDate = today.minusDays(20))
        event(f = f, name = "Ended Recently", startDate = today.minusDays(5), endDate = today.minusDays(1))

        namesIn(f = f, bucket = EventBucket.UPCOMING) shouldBe listOf("Sooner", "Later")
        namesIn(f = f, bucket = EventBucket.UNFINALIZED) shouldBe listOf("Ended Recently", "Ended Long Ago")
    }

    @Test
    fun `Finalized sorts newest-finalized first (#786)`() {
        val f = fixture()
        val older = event(f = f, name = "Finalized Earlier", startDate = today.minusDays(9), endDate = today.minusDays(8))
        val newer = event(f = f, name = "Finalized Later", startDate = today.minusDays(3), endDate = today.minusDays(2))
        events.finalize(id = older, finalizedBy = f.host.id, finalizedAt = LocalDateTime.now().minusDays(2))
        events.finalize(id = newer, finalizedBy = f.host.id, finalizedAt = LocalDateTime.now())

        namesIn(f = f, bucket = EventBucket.FINALIZED) shouldBe listOf("Finalized Later", "Finalized Earlier")
    }

    @Test
    fun `a bucket pages ten at a time and reports the whole bucket's total (#786)`() {
        val f = fixture()
        // 12 upcoming events, deterministically ordered by start date.
        (1..12).forEach { i ->
            event(f = f, name = "Event %02d".format(i), startDate = today.plusDays(i.toLong()), endDate = today.plusDays(i + 1L))
        }

        val first =
            reader
                .publicEventsByCode(code = f.publicCode, bucket = EventBucket.UPCOMING.name, limit = 10, offset = 0)
                .shouldBeRight()
        first.items.map { it.name } shouldBe (1..10).map { "Event %02d".format(it) }
        // total is the size of the BUCKET, not the page — so a pager can say "Showing 1–10 of 12".
        first.total shouldBe 12L

        val second =
            reader
                .publicEventsByCode(code = f.publicCode, bucket = EventBucket.UPCOMING.name, limit = 10, offset = 10)
                .shouldBeRight()
        second.items.map { it.name } shouldBe listOf("Event 11", "Event 12")
        second.total shouldBe 12L
    }

    @Test
    fun `an offset past the end yields an empty page, not an error (#786)`() {
        val f = fixture()
        event(f = f, name = "Only One", startDate = today.plusDays(1), endDate = today.plusDays(2))

        val page =
            reader
                .publicEventsByCode(code = f.publicCode, bucket = EventBucket.UPCOMING.name, limit = 10, offset = 500)
                .shouldBeRight()
        page.items.shouldBeEmpty()
        page.total shouldBe 1L
    }

    @Test
    fun `an outsized page size is coerced rather than honoured (#786)`() {
        val f = fixture()
        (1..3).forEach { i ->
            event(f = f, name = "Event $i", startDate = today.plusDays(i.toLong()), endDate = today.plusDays(i + 1L))
        }

        // 100 is the ceiling; asking for more must not turn the endpoint into an unbounded scan.
        reader
            .publicEventsByCode(code = f.publicCode, bucket = EventBucket.UPCOMING.name, limit = 5_000, offset = 0)
            .shouldBeRight()
            .items
            .size shouldBe 3
    }

    @Test
    fun `another club's events never appear in this club's buckets (#786)`() {
        val f = fixture()
        event(f = f, name = "Ours", startDate = today.plusDays(1), endDate = today.plusDays(2))
        val other = clubs.create(command = CreateClubCommand(name = "West End", createdBy = f.host.id)).toDomain()
        events.create(
            command =
                CreateEventCommand(
                    name = "Theirs",
                    startDate = today.plusDays(1),
                    endDate = today.plusDays(2),
                    participantIds = listOf(element = f.player.id),
                    createdBy = f.host.id,
                    clubId = other.id,
                ),
        )

        namesIn(f = f, bucket = EventBucket.UPCOMING) shouldBe listOf(element = "Ours")
    }

    @Test
    fun `an unknown or missing bucket is a validation failure, not a silent default (#786)`() {
        val f = fixture()

        // Parsing lives in the service (routes must not depend on `model`), so a bad value surfaces as a
        // Validation the route maps to 400 — never as a quiet fallback to some other bucket.
        reader.publicEventsByCode(code = f.publicCode, bucket = "NOPE", limit = 10, offset = 0)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        reader.publicEventsByCode(code = f.publicCode, bucket = null, limit = 10, offset = 0)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }
}
