// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.seeding

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.event.CreateEventInput
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedFixtureClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SeedingServiceTest {
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
    private val lists = PlayerListService()
    private val eventService = EventService()
    private val service = SeedingService()

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
        ).toDomain()

    /** A PLAYER with no display name → seeding falls back to the public code. */
    private fun provisionUnnamed(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = emptyList(),
                    capabilities = setOf(element = Capability.PLAYER),
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    // Confidence is computed (#459) from windowed match sparsity: [matches] in-window completed open-play
    // matches raise a player's confidence (more matches → denser play → higher confidence). `matches` = 0
    // leaves a bare override with no qualifying play → confidence 0.
    private var sparringCounter = 0

    private fun rate(
        user: User,
        value: String,
        matches: Int = 0,
    ) {
        val level = value.toBigDecimal().let { "${it.toInt()}.${if (it.toDouble() % 1.0 >= 0.5) 5 else 0}" }
        ratings.setRating(userId = user.id, rating = BigDecimal(value), level = level)
        repeat(times = matches) {
            val opponent = provision(uid = "spar-${sparringCounter++}")
            val match =
                matchRepo.createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1UserIds = listOf(element = user.id),
                            team2UserIds = listOf(element = opponent.id),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = user.id,
                        ),
                ).toDomain()
            matchRepo.addResult(
                matchId = match.id,
                sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId)),
                winnerTeamId = match.team1.teamId,
                recordedBy = user.id,
                completedAt = LocalDateTime.now(),
            )
        }
    }

    /** A HOST with a list containing [members]; returns the list id. */
    private fun listWith(members: List<User>): UUID {
        val list = lists.create(token = token(uid = "host"), name = "Seeded").shouldBeRight()
        members.forEach { lists.addMember(token = token(uid = "host"), listId = UUID.fromString(list.id), userId = it.id).shouldBeRight() }
        return UUID.fromString(list.id)
    }

    /** A HOST-owned event with [members] as its APPROVED roster (#714); returns the event id. */
    private fun eventWith(members: List<User>): UUID {
        val created =
            eventService.create(
                token = token(uid = "host"),
                input =
                    CreateEventInput(
                        clubId = seedFixtureClub(ownerUids = arrayOf("host")).id,
                        name = "Club Open",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now(),
                        participantIds = members.map { it.id },
                    ),
            ).shouldBeRight()
        return UUID.fromString(created.id)
    }

    @Test
    fun `generate sorts by rating descending and seeds the top half (round up)`() {
        // ADMINISTRATOR so the raw rating is surfaced in the DTO (#583) for the exact-rating assertion below.
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.ADMINISTRATOR))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val listId = listWith(members = listOf(a, b, c))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        seeding.entries shouldHaveSize 3
        // Order is rating-desc: alice (4.5), carol (4.0), bob (3.5).
        seeding.entries.map { it.userId } shouldBe listOf(a.id.toString(), c.id.toString(), b.id.toString())
        seeding.entries.map { it.position } shouldBe listOf(1, 2, 3)
        // Top ⌈3/2⌉ = 2 are seeded; the rest blank.
        seeding.entries.map { it.seed } shouldBe listOf(1, 2, null)
        // The exact rating is captured (stored at scale 6, so compare numerically).
        seeding.entries.first().rating.shouldNotBeNull().toBigDecimal().compareTo(other = BigDecimal("4.5")) shouldBe 0
    }

    @Test
    fun `rating ties break by confidence, then matches, then name`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val low = provision(uid = "zoe").also { rate(user = it, value = "4.0", matches = 1) }
        val high = provision(uid = "amy").also { rate(user = it, value = "4.0", matches = 5) }
        val listId = listWith(members = listOf(low, high))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        // Same rating → higher confidence first.
        seeding.entries.map { it.userId } shouldBe listOf(high.id.toString(), low.id.toString())
    }

    @Test
    fun `ties on rating and confidence break by name, falling back to the player code`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        // All equal on rating and confidence, so only the name (or code) tie-break decides the order.
        val bravo = provision(uid = "Bravo").also { rate(user = it, value = "4.0", matches = 5) }
        val alpha = provision(uid = "Alpha").also { rate(user = it, value = "4.0", matches = 5) }
        val unnamed = provisionUnnamed(uid = "nameless").also { rate(user = it, value = "4.0", matches = 5) }
        val listId = listWith(members = listOf(bravo, alpha, unnamed))

        val ids = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight().entries.map { it.userId }
        ids shouldHaveSize 3
        // "Alpha" sorts before "Bravo"; the unnamed player is ordered by its public code (position not asserted).
        (ids.indexOf(element = alpha.id.toString()) < ids.indexOf(element = bravo.id.toString())) shouldBe true
        (unnamed.id.toString() in ids) shouldBe true
    }

    @Test
    fun `only members with a rating are seeded`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val rated = provision(uid = "rated").also { rate(user = it, value = "4.0") }
        val unrated = provision(uid = "unrated")
        val listId = listWith(members = listOf(rated, unrated))

        val seeding = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        seeding.entries.map { it.userId } shouldBe listOf(element = rated.id.toString())
    }

    @Test
    fun `regenerating overwrites the previous seeding`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.0") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val listId = listWith(members = listOf(a, b))

        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        // Bump bob above alice, then regenerate.
        rate(user = b, value = "5.0")
        val regenerated = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        regenerated.entries.map { it.userId } shouldBe listOf(b.id.toString(), a.id.toString())
        // Still a single current seeding.
        service.get(token = token(uid = "host"), listId = listId).shouldBeRight().entries shouldHaveSize 2
    }

    @Test
    fun `saveOrder persists the host order, renumbers seeds 1 to N, and marks manually edited`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val listId = listWith(members = listOf(a, b, c))
        // Generate first (rating-desc = a, c, b), then save a hand order that fully reverses it.
        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()

        val saved =
            service.saveOrder(token = token(uid = "host"), listId = listId, orderedUserIds = listOf(b.id, c.id, a.id))
                .shouldBeRight()
        saved.entries.map { it.userId } shouldBe listOf(b.id.toString(), c.id.toString(), a.id.toString())
        saved.entries.map { it.position } shouldBe listOf(1, 2, 3)
        // Reordering reassigns seeds 1..N by position — every row is seeded, old numbers not preserved.
        saved.entries.map { it.seed } shouldBe listOf(1, 2, 3)
        saved.manuallyEdited shouldBe true

        // The saved order + flag survive a read, and there is still a single current seeding.
        val readBack = service.get(token = token(uid = "host"), listId = listId).shouldBeRight()
        readBack.entries.map { it.userId } shouldBe listOf(b.id.toString(), c.id.toString(), a.id.toString())
        readBack.manuallyEdited shouldBe true
    }

    @Test
    fun `saveOrder rejects an order that is not exactly the seedable set`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val listId = listWith(members = listOf(a, b))
        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()

        // Missing bob → not a permutation of the seedable set.
        service.saveOrder(token = token(uid = "host"), listId = listId, orderedUserIds = listOf(element = a.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `regenerating after a manual save resets the manually-edited flag`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val listId = listWith(members = listOf(a, b))
        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        service.saveOrder(token = token(uid = "host"), listId = listId, orderedUserIds = listOf(b.id, a.id)).shouldBeRight()

        val regenerated = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        regenerated.manuallyEdited shouldBe false
    }

    @Test
    fun `saving order on someone else's list is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "intruder", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.0") }
        val listId = listWith(members = listOf(element = a))
        service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()

        service.saveOrder(token = token(uid = "intruder"), listId = listId, orderedUserIds = listOf(element = a.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `get returns not-found before a seeding is generated`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val listId = listWith(members = emptyList())
        service.get(token = token(uid = "host"), listId = listId).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `generating someone else's list is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "intruder", roles = setOf(Capability.PLAYER, Capability.HOST))
        val listId = listWith(members = emptyList())

        service.generate(token = token(uid = "intruder"), listId = listId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    // ---- Event-sourced seeding (#714) ---------------------------------------------------------

    @Test
    fun `generateForEvent sorts by rating descending and seeds the top half (round up)`() {
        // ADMINISTRATOR so the raw rating is surfaced in the DTO (#583) for the exact-rating assertion below.
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.ADMINISTRATOR))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val eventId = eventWith(members = listOf(a, b, c))

        val seeding = service.generateForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight()
        seeding.entries shouldHaveSize 3
        seeding.entries.map { it.userId } shouldBe listOf(a.id.toString(), c.id.toString(), b.id.toString())
        seeding.entries.map { it.position } shouldBe listOf(1, 2, 3)
        seeding.entries.map { it.seed } shouldBe listOf(1, 2, null)
        seeding.entries.first().rating.shouldNotBeNull().toBigDecimal().compareTo(other = BigDecimal("4.5")) shouldBe 0
    }

    @Test
    fun `event and list seedings produce identical ordering for the same players`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val listId = listWith(members = listOf(a, b, c))
        val eventId = eventWith(members = listOf(a, b, c))

        val fromList = service.generate(token = token(uid = "host"), listId = listId).shouldBeRight()
        val fromEvent = service.generateForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight()
        // The shared sort + snapshot mapping must not diverge between the two sources.
        fromEvent.entries.map { it.userId } shouldBe fromList.entries.map { it.userId }
        fromEvent.entries.map { it.seed } shouldBe fromList.entries.map { it.seed }
    }

    @Test
    fun `regenerating an event seeding overwrites the previous one`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.0") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val eventId = eventWith(members = listOf(a, b))

        service.generateForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight()
        rate(user = b, value = "5.0")
        val regenerated = service.generateForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight()
        regenerated.entries.map { it.userId } shouldBe listOf(b.id.toString(), a.id.toString())
        service.getForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight().entries shouldHaveSize 2
    }

    @Test
    fun `saveOrderForEvent persists the host order, renumbers seeds, and marks manually edited (#718)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "alice").also { rate(user = it, value = "4.5") }
        val b = provision(uid = "bob").also { rate(user = it, value = "3.5") }
        val c = provision(uid = "carol").also { rate(user = it, value = "4.0") }
        val eventId = eventWith(members = listOf(a, b, c))
        service.generateForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight()

        val saved =
            service.saveOrderForEvent(token = token(uid = "host"), eventId = eventId, orderedUserIds = listOf(c.id, a.id, b.id))
                .shouldBeRight()
        saved.entries.map { it.userId } shouldBe listOf(c.id.toString(), a.id.toString(), b.id.toString())
        saved.entries.map { it.seed } shouldBe listOf(1, 2, 3)
        saved.manuallyEdited shouldBe true

        service.getForEvent(token = token(uid = "host"), eventId = eventId).shouldBeRight().manuallyEdited shouldBe true
    }

    @Test
    fun `getForEvent returns not-found before a seeding is generated`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val eventId = eventWith(members = emptyList())
        service.getForEvent(token = token(uid = "host"), eventId = eventId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `generating a seeding for an event you don't host is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "intruder", roles = setOf(Capability.PLAYER, Capability.HOST))
        val eventId = eventWith(members = emptyList())

        service.generateForEvent(token = token(uid = "intruder"), eventId = eventId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
