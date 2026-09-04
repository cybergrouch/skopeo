// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.BandRelation
import org.skopeo.common.contract.FullMatchPointsConfig
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.ranking.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.CreateCircuitCommand
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.CreatePlaceholderCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventParticipantStatus
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.model.ageInYears
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventsTable
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestAppSettings
import org.skopeo.testsupport.settleAllRatings
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class EventServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val clubs = ClubRepository()
    private val service = EventService(events = events, users = users)

    // The service now returns EventResponse DTOs; re-fetch the persisted domain Event for assertions
    // and fixtures that need domain-typed fields absent from the DTO (createdBy, participantIds,
    // finalizedBy, type as EventType, dates as LocalDate).
    private fun EventResponse.domain(): Event = events.findById(id = UUID.fromString(id))!!.toDomain()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
        fixtureClubs.clear()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        ).toDomain()

    // A login-less placeholder ("dummy") player (#496/#505) — a real users row with placeholder = true.
    private fun placeholder(displayName: String): User =
        users.createPlaceholder(command = CreatePlaceholderCommand(displayName = displayName, sex = "Male")).toDomain()

    private val fixtureClubs = mutableMapOf<String, UUID>()

    /**
     * A club owned by [ownerUid], one per owner per test. Every event needs a club (#794) and its creator
     * must own it (#789) — and the club must be owned by ONLY that creator, or the authz-refusal tests
     * ("a host cannot rename another host's event") would start passing for the wrong reason.
     */
    private fun fixtureClub(ownerUid: String): UUID = fixtureClubs.getOrPut(key = ownerUid) { clubOwnedBy(ownerUid = ownerUid) }

    private fun clubOwnedBy(ownerUid: String): UUID {
        val owner = requireNotNull(value = users.findByFirebaseUid(firebaseUid = ownerUid)).toDomain()
        val club = clubs.create(command = CreateClubCommand(name = "Fixture TC", createdBy = owner.id)).toDomain()
        clubs.addOwner(clubId = club.id, userId = owner.id)
        return club.id
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    // A club [owner] is a NAMED OWNER of (#789). `created_by` on a club is provenance, not ownership, so
    // the club_owners row is what event authorization actually reads.
    private fun ownedClub(
        name: String,
        owner: User,
    ) = clubs
        .create(command = CreateClubCommand(name = name, createdBy = owner.id))
        .toDomain()
        .also { clubs.addOwner(clubId = it.id, userId = owner.id) }

    // Default to a currently-running event (ends a week out) so host data-entry stays allowed; the
    // expired-event tests (#310) pass explicit past dates.
    private fun input(
        name: String = "Spring Open",
        start: String = LocalDate.now().toString(),
        end: String = LocalDate.now().plusDays(7).toString(),
        participants: List<UUID> = emptyList(),
        clubId: UUID? = null,
        // Whose club the event is filed under when [clubId] is absent; the creator must own it (#789).
        ownerUid: String = "host",
        circuitId: UUID? = null,
        type: EventType = EventType.OPEN_PLAY,
        format: String = "SINGLES",
        awardRankingPoints: Boolean = true,
    ) = CreateEventInput(
        name = name,
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        participantIds = participants,
        clubId = clubId ?: fixtureClub(ownerUid = ownerUid),
        circuitId = circuitId,
        type = type.name,
        format = format,
        awardRankingPoints = awardRankingPoints,
    )

    // A create input for an event under a club (#559: no per-event points config — awarding follows the
    // event's single "award ranking points" flag, which defaults on).
    private fun clubInput(
        clubId: UUID,
        type: EventType = EventType.OPEN_PLAY,
    ) = input(
        clubId = clubId,
        type = type,
    )

    @Test
    fun `callerCanSeeRawRating is true only for an administrator (#583)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.callerCanSeeRawRating(token = token(uid = "admin")).shouldBeTrue()
        service.callerCanSeeRawRating(token = token(uid = "host")).shouldBeFalse()
        service.callerCanSeeRawRating(token = token(uid = "ghost")).shouldBeFalse()
    }

    @Test
    fun `a host creates an event with a resolved participant roster`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")

        val view =
            service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight()
        view.name shouldBe "Spring Open"
        view.publicCode.length shouldBe 6
        view.participants.map { it.userId }.shouldContainExactlyInAnyOrder(p1.id.toString(), p2.id.toString())
        // Participant order isn't guaranteed, so look p1 up by id rather than assuming it's first.
        view.participants.single { it.userId == p1.id.toString() }.displayName shouldBe "p1"
        // Every event is filed under a club (#794); there is no clubless default any more.
        view.club.shouldNotBeNull().id shouldBe fixtureClub(ownerUid = "host").toString()
    }

    @Test
    fun `create rejects an unknown event type (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service
            .create(token = token(uid = "host"), input = input().copy(type = "NOT_A_TYPE"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `event participants carry the placeholder flag, true for a dummy and false for a real player (#505)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val real = provision(uid = "real")
        val dummy = placeholder(displayName = "Dummy")

        val view =
            service.create(token = token(uid = "host"), input = input(participants = listOf(real.id, dummy.id))).shouldBeRight()

        // The DTO carries the placeholder flag through verbatim as isPlaceholder (#505).
        view.participants.single { it.userId == dummy.id.toString() }.isPlaceholder.shouldBeTrue()
        view.participants.single { it.userId == real.id.toString() }.isPlaceholder.shouldBeFalse()
    }

    @Test
    fun `a soft-deleted account cannot be added to a new event or an existing one (#518)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val gone = provision(uid = "gone")
        users.deactivate(id = gone.id).shouldBeRight()

        // Rejected at event creation.
        service
            .create(token = token(uid = "host"), input = input(participants = listOf(element = gone.id)))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // And rejected when adding to an already-created event (existing rosters are untouched).
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()
        service
            .addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = gone.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `event participants carry isDeleted, true for a deleted account and false for a real player (#518)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val real = provision(uid = "real")
        val gone = provision(uid = "gone")
        // Build the roster while both are active, then delete one (existing references are retained).
        val event =
            service.create(token = token(uid = "host"), input = input(participants = listOf(real.id, gone.id))).shouldBeRight()
        users.deactivate(id = gone.id).shouldBeRight()

        val participants = service.publicByCode(token = token(uid = "host"), code = event.publicCode).shouldBeRight().participants
        participants.single { it.userId == gone.id.toString() }.isDeleted.shouldBeTrue()
        participants.single { it.userId == real.id.toString() }.isDeleted.shouldBeFalse()
    }

    @Test
    fun `a merged duplicate is not flagged deleted on the roster (#518)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val canonical = provision(uid = "canon")
        val dup = provision(uid = "dup")
        val event =
            service.create(token = token(uid = "host"), input = input(participants = listOf(element = dup.id))).shouldBeRight()
        // Mark dup a merged duplicate AFTER it's on the roster: inactive but with a canonical pointer.
        users.markDuplicates(canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))

        val participants = service.publicByCode(token = token(uid = "host"), code = event.publicCode).shouldBeRight().participants
        // A merged duplicate is NOT deleted (canonical != null), so isDeleted stays false.
        participants.single { it.userId == dup.id.toString() }.isDeleted.shouldBeFalse()
    }

    @Test
    fun `creating an event writes an Activity Log entry (#334)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val view = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe UUID.fromString(view.id)
            it.summary shouldBe "Created event Spring Open"
            it.details["publicCode"] shouldBe view.publicCode
        }
    }

    @Test
    fun `an event can be created under a club, and an unknown club is rejected (#313)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        // The host must OWN the club to file under it (#789).
        val club = ownedClub(name = "Downtown TC", owner = host)

        // A club event of any type now requires a points config (OPEN_PLAY unified); supply a valid window.
        val view = service.create(token = token(uid = "host"), input = clubInput(clubId = club.id)).shouldBeRight()
        // One nested object (#780): present-with-everything, or absent entirely. Its public code lets a
        // club reference link to the club's public page without a second lookup — the events list is
        // read by viewers who cannot list clubs.
        view.club.shouldNotBeNull().let {
            it.id shouldBe club.id.toString()
            it.name shouldBe "Downtown TC"
            it.publicCode shouldBe club.publicCode
        }

        // An unknown club is rejected at create.
        service.create(token = token(uid = "host"), input = clubInput(clubId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an administrator re-files a FINALIZED event's club, and a host still cannot (#782)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val clubA = ownedClub(name = "Downtown TC", owner = host)
        // Deliberately NOT owned by the host: only the administrator can re-file the event here (#789).
        val clubB = clubs.create(command = CreateClubCommand(name = "West End", createdBy = host.id)).toDomain()
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.setClub(token = token(uid = "host"), id = event.id, clubId = clubA.id).shouldBeRight()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The host who filed it can no longer touch the club once finalized — the terminal rule holds.
        service
            .setClub(token = token(uid = "host"), id = event.id, clubId = clubB.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // An administrator may, because the club is not an input to the rating calculation (#782).
        service.setClub(token = token(uid = "admin"), id = event.id, clubId = clubB.id).shouldBeRight().let {
            it.club.shouldNotBeNull().id shouldBe clubB.id.toString()
            // Still finalized: this is a bookkeeping correction, not an un-finalize.
            it.isFinalized.shouldBeTrue()
        }
        events.findById(id = event.id)!!.toDomain().let {
            it.clubId shouldBe clubB.id
            it.isFinalized.shouldBeTrue()
            it.finalizedAt.shouldNotBeNull()
        }

        // Clearing back to "Open" is no longer possible (#794) — an admin re-files between clubs instead.
        service.setClub(token = token(uid = "admin"), id = event.id, clubId = clubA.id).shouldBeRight().let {
            it.club.shouldNotBeNull().id shouldBe clubA.id.toString()
            it.isFinalized.shouldBeTrue()
        }
    }

    @Test
    fun `re-filing a finalized event records that it happened after finalize (#782)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id)).toDomain()
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.setClub(token = token(uid = "admin"), id = event.id, clubId = club.id).shouldBeRight()

        AuditRepository()
            .list(actions = listOf(element = AuditAction.EVENT_CLUB_CHANGED), limit = 10, offset = 0)
            .first
            .first()
            .let {
                it.summary shouldContain "after finalize"
                it.details["wasFinalized"] shouldBe "true"
                // An OPEN_PLAY event pays from band difference and never consults the club, so there are
                // no placement points to leave as-issued.
                it.details["placementPointsLeftAsIssued"] shouldBe "false"
            }
    }

    @Test
    fun `setClub re-files an event from one club to another, both ways (#319)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val clubA = ownedClub(name = "Downtown TC", owner = host)
        val clubB = ownedClub(name = "West End", owner = host)
        // Every event is filed under a club from birth (#794), so there is no clubless start state to add
        // one to, and no clearing back to "Open" at the end — setClub is purely a move between clubs.
        val event = service.create(token = token(uid = "host"), input = clubInput(clubId = clubA.id)).shouldBeRight()
        event.club.shouldNotBeNull().id shouldBe clubA.id.toString()

        // Move it. Re-filing swaps the whole nested object, so the link always points at the CURRENT club.
        service.setClub(token = token(uid = "host"), id = UUID.fromString(event.id), clubId = clubB.id).shouldBeRight().let {
            it.club.shouldNotBeNull().let { c ->
                c.id shouldBe clubB.id.toString()
                c.name shouldBe "West End"
                c.publicCode shouldBe clubB.publicCode
            }
        }
        // And back — the move is symmetric.
        service.setClub(token = token(uid = "host"), id = UUID.fromString(event.id), clubId = clubA.id)
            .shouldBeRight()
            .club
            .shouldNotBeNull()
            .id shouldBe clubA.id.toString()

        // Each move writes an Activity Log entry (#354). Look them up by destination club rather than by
        // position — two writes a moment apart can tie on occurred_at, so ordering is not load-bearing.
        val entries = AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CLUB_CHANGED), limit = 10, offset = 0).first
        entries shouldHaveSize 2
        entries.map { it.actorUserId }.toSet() shouldBe setOf(element = host.id)
        entries.single { it.details["newClubId"] == clubB.id.toString() }.let {
            it.summary shouldBe "Re-filed event ${event.name} under club ${clubB.id}"
            it.details["oldClubId"] shouldBe clubA.id.toString()
        }
        entries.single { it.details["newClubId"] == clubA.id.toString() }.let {
            it.summary shouldBe "Re-filed event ${event.name} under club ${clubA.id}"
            it.details["oldClubId"] shouldBe clubB.id.toString()
        }
    }

    @Test
    fun `setClub validates the club and is owner-or-admin only (#319)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id)).toDomain()
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        // Unknown club → Validation.
        service.setClub(token = token(uid = "host"), id = UUID.fromString(event.id), clubId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // A different host (not the creator) → Forbidden.
        service.setClub(token = token(uid = "other"), id = UUID.fromString(event.id), clubId = club.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        // An ADMINISTRATOR may edit any event's club.
        service
            .setClub(token = token(uid = "admin"), id = UUID.fromString(event.id), clubId = club.id)
            .shouldBeRight().club.shouldNotBeNull().id shouldBe club.id.toString()
        // Unknown event → NotFound.
        service.setClub(token = token(uid = "admin"), id = UUID.randomUUID(), clubId = club.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setCalcPriority persists and is administrator-only (#335)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        // A non-admin staff member (host) cannot set the calculation priority.
        service.setCalcPriority(token = token(uid = "host"), id = UUID.fromString(event.id), priority = 5.0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An ADMINISTRATOR sets it, and it round-trips on the view.
        service.setCalcPriority(token = token(uid = "admin"), id = UUID.fromString(event.id), priority = 5.0)
            .shouldBeRight().domain().calcPriority shouldBe 5.0
        // Unknown event → NotFound.
        service.setCalcPriority(token = token(uid = "admin"), id = UUID.randomUUID(), priority = 1.0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `the organizer view surfaces the filing host as the creator (#270)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val created = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        created.creatorDisplayName shouldBe "host"
        created.creatorPublicCode shouldBe host.publicCode
        // Also present on the list and single-event views (both go through toView).
        service.list(token = token(uid = "host")).shouldBeRight().single().creatorPublicCode shouldBe host.publicCode
        service
            .get(token = token(uid = "host"), id = UUID.fromString(created.id))
            .shouldBeRight().creatorPublicCode shouldBe host.publicCode
    }

    @Test
    fun `an event whose creator was removed has a null creator, and falls back to its club (#270)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "host"), input = input(ownerUid = "host")).shouldBeRight().domain()

        // Orphan the creator — the FK is ON DELETE SET NULL (created_by becomes null).
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[createdBy] = null } }

        // The creator attribution is gone from the view.
        service.get(token = token(uid = "admin"), id = event.id).shouldBeRight().creatorPublicCode.shouldBeNull()
        // Losing the creator no longer strands the event: since #794 it always has a club, and that club's
        // named owner is still an ownership anchor (#789). A host who owns neither is refused.
        service.get(token = token(uid = "host"), id = event.id).shouldBeRight().creatorPublicCode.shouldBeNull()
        service.get(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `participant roster carries sex, age, and the current rating band`() {
        // The raw NTRP value on the roster is ADMINISTRATOR-only (#583); provision the caller as an admin
        // so the reveal flag is on, and read the roster back through list() (which applies that flag).
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.HOST, Capability.ADMINISTRATOR))
        val dob = LocalDate.parse("1990-09-09")
        val player =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "p1".asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "p1", isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Maria")),
                        sex = "Female",
                        dateOfBirth = dob.asRedactable(),
                    ),
            ).toDomain()
        RatingRepository().setRating(
            userId = player.id,
            rating = BigDecimal("4.000000"),
            level = "4.0",
        )

        service
            .create(token = token(uid = "admin"), input = input(participants = listOf(element = player.id), ownerUid = "admin"))
            .shouldBeRight()
        val view = service.list(token = token(uid = "admin")).shouldBeRight().single()
        val participant = view.participants.single()
        participant.sex shouldBe "Female"
        participant.age shouldBe ageInYears(dateOfBirth = dob, asOf = LocalDate.now())
        participant.rating?.value shouldBe "4.000000"
        participant.rating?.level shouldBe "4.0"
    }

    @Test
    fun `create rejects a blank name, an inverted date range, and an unknown participant`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), input = input(name = "  "))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(token = token(uid = "host"), input = input(start = "2026-03-05", end = "2026-03-01"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(token = token(uid = "host"), input = input(participants = listOf(element = UUID.randomUUID())))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `list is scoped - a host sees only their own events, an admin sees all`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host1", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "host2", roles = setOf(Capability.PLAYER, Capability.HOST))
        // Each host files under their OWN club — a shared one would put both events in host1's scoped list.
        val a = service.create(token = token(uid = "host1"), input = input(name = "H1 Cup", ownerUid = "host1")).shouldBeRight()
        val b = service.create(token = token(uid = "host2"), input = input(name = "H2 Cup", ownerUid = "host2")).shouldBeRight()

        service.list(token = token(uid = "host1")).shouldBeRight().map { it.id } shouldBe listOf(element = a.id)
        service.list(token = token(uid = "admin")).shouldBeRight().map { it.id }.toSet() shouldBe setOf(a.id, b.id)
    }

    @Test
    fun `get returns the event or a not-found`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.get(token = token(uid = "host"), id = UUID.fromString(event.id)).shouldBeRight().id shouldBe event.id
        service.get(token = token(uid = "host"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `add and remove participants, idempotently`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = p1.id).shouldBeRight()
        // Re-adding the same participant is a no-op (no duplicate).
        val after = service.addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = p1.id).shouldBeRight()
        after.participants shouldHaveSize 1

        val removed =
            service.removeParticipant(
                token = token(uid = "host"),
                eventId = UUID.fromString(event.id),
                userId = p1.id,
            ).shouldBeRight()
        removed.participants shouldHaveSize 0
    }

    @Test
    fun `a host cannot add a participant to an expired event, but an admin or club owner can (#310)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val clubOwner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // The event is filed under a club BOTH staff members own (#789) — expiry (#310) is the axis under
        // test here, so club ownership must not be what refuses anyone.
        val club = ownedClub(name = "Downtown TC", owner = clubOwner)
        clubs.addOwner(clubId = club.id, userId = host.id)
        val expired =
            input(
                start = LocalDate.now().minusDays(3).toString(),
                end = LocalDate.now().minusDays(1).toString(),
                clubId = club.id,
            )
        val event = service.create(token = token(uid = "host"), input = expired).shouldBeRight()

        // The event has ended → the HOST is blocked.
        service.addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = p1.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // An ADMINISTRATOR may still add.
        service.addParticipant(token = token(uid = "admin"), eventId = UUID.fromString(event.id), userId = p1.id)
            .shouldBeRight()
            .participants shouldHaveSize 1

        // A CLUB_OWNER may still add too (#310 follow-up).
        service.addParticipant(token = token(uid = "owner"), eventId = UUID.fromString(event.id), userId = p2.id)
            .shouldBeRight()
            .participants shouldHaveSize 2
    }

    @Test
    fun `a host may still add on the event's last day (#310)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val endsToday = input(start = LocalDate.now().minusDays(2).toString(), end = LocalDate.now().toString())
        val event = service.create(token = token(uid = "host"), input = endsToday).shouldBeRight()

        // today == endDate is not yet expired → still allowed.
        service.addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = p1.id).shouldBeRight()
    }

    @Test
    fun `adding to a missing event is a not-found, adding an unknown user is a validation error`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.addParticipant(token = token(uid = "host"), eventId = UUID.randomUUID(), userId = provision(uid = "p9").id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.addParticipant(token = token(uid = "host"), eventId = UUID.fromString(event.id), userId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // Removing from a missing event is also a not-found.
        service.removeParticipant(token = token(uid = "host"), eventId = UUID.randomUUID(), userId = provision(uid = "p8").id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a non-staff player cannot create, list, get, or manage participants`() {
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.create(token = token(uid = "player"), input = input()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.get(
            token = token(uid = "player"),
            id = UUID.fromString(event.id),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.addParticipant(token = token(uid = "player"), eventId = UUID.fromString(event.id), userId = UUID.fromString(event.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.removeParticipant(token = token(uid = "player"), eventId = UUID.fromString(event.id), userId = UUID.fromString(event.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `publicByCode returns the event with participants and its matches, or NotFound (#138)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val created = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight()
        MatchRepository().createFixture(
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
                    eventId = UUID.fromString(created.id),
                ),
        )

        val public = service.publicByCode(token = token(uid = "host"), code = created.publicCode).shouldBeRight()
        public.name shouldBe "Spring Open"
        public.participants.map { it.userId }.toSet() shouldBe setOf(p1.id.toString(), p2.id.toString())
        public.matches shouldHaveSize 1
        public.matches.single().team1.single().publicCode shouldBe p1.publicCode

        service
            .publicByCode(token = token(uid = "host"), code = "ZZZZZZ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `publicByCode surfaces the organizing club's name (#313)`() {
        // The "and null when clubless" half of this test is gone with #794: every event has a club, so the
        // public page always has a club name to show.
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = ownedClub(name = "Downtown TC", owner = host)
        val underClub = service.create(token = token(uid = "host"), input = clubInput(clubId = club.id)).shouldBeRight()

        service.publicByCode(token = null, code = underClub.publicCode).shouldBeRight().clubName shouldBe "Downtown TC"
    }

    // --- Event deletion (#243) ---

    private val matchRepo = MatchRepository()

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
        ).toDomain()

    private fun recordResult(match: Match) {
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = match.createdBy!!,
            completedAt = LocalDateTime.now(),
        )
    }

    @Test
    fun `setActive reports false when no event row matches`() {
        events.setActive(id = UUID.randomUUID(), active = false, disabledAt = null).shouldBeFalse()
    }

    @Test
    fun `a host deletes an event with no matches, and it drops off their list`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()

        events.findById(id = event.id)!!.toDomain().isActive.shouldBeFalse()
        service.list(token = token(uid = "host")).shouldBeRight() shouldHaveSize 0
        // The soft-delete writes an Activity Log entry (#354).
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_DELETED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Deleted event ${event.name}"
        }
    }

    @Test
    fun `deleting an event soft-disables its remaining scheduled fixtures`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().domain()
        seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()

        matchRepo.listByEvent(eventId = event.id) shouldHaveSize 0
    }

    @Test
    fun `an event with a recorded but unrated match is refused, advising match deletion first`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().domain()
        recordResult(match = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2))

        val error = service.delete(token = token(uid = "host"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "recorded matches first"
        events.findById(id = event.id)!!.toDomain().isActive.shouldBeTrue()
    }

    @Test
    fun `an event with a rated match cannot be deleted`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().domain()
        val match = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        recordResult(match = match)
        matchRepo.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        val error = service.delete(token = token(uid = "host"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "rated matches"
        events.findById(id = event.id)!!.toDomain().isActive.shouldBeTrue()
    }

    /**
     * The event list's "Rated" badge (#772). Binary on purpose: a rating run finishes in seconds, so a
     * half-covered event is transient and reads as not-yet-rated rather than as a third state.
     */
    @Test
    fun `list reports isRated only once every recorded result in the event is rated (#772)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().domain()
        val first = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        val second = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        fun listed() = service.list(token = token(uid = "host")).shouldBeRight().single { it.id == event.id.toString() }

        // No results yet: nothing to rate is not the same as everything rated.
        listed().isRated.shouldBeFalse()

        recordResult(match = first)
        recordResult(match = second)
        listed().isRated.shouldBeFalse()

        // One of two rated — a run in progress still reads as not rated.
        matchRepo.markRated(matchId = first.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)
        listed().isRated.shouldBeFalse()

        matchRepo.markRated(matchId = second.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)
        listed().isRated.shouldBeTrue()
    }

    @Test
    fun `a host cannot delete another host's event, but an administrator can`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input(ownerUid = "owner")).shouldBeRight().domain()

        service.delete(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.toDomain().isActive.shouldBeTrue()

        service.delete(token = token(uid = "admin"), id = event.id).shouldBeRight()
        events.findById(id = event.id)!!.toDomain().isActive.shouldBeFalse()
        owner.id shouldBe event.createdBy
    }

    @Test
    fun `a host renames their own event, trimming the name, and it writes an Activity Log entry (#354)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input(name = "Spring Open")).shouldBeRight().domain()

        val renamed = service.rename(token = token(uid = "host"), id = event.id, name = "  Summer Classic  ").shouldBeRight()

        renamed.name shouldBe "Summer Classic"
        events.findById(id = event.id)!!.toDomain().name shouldBe "Summer Classic"
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_RENAMED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Renamed event Spring Open → Summer Classic"
            it.details["oldName"] shouldBe "Spring Open"
        }
    }

    @Test
    fun `rename rejects a blank name`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        service.rename(token = token(uid = "host"), id = event.id, name = "   ")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        events.findById(id = event.id)!!.toDomain().name shouldBe "Spring Open"
    }

    @Test
    fun `a host cannot rename another host's event, but an administrator can`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event =
            service.create(token = token(uid = "owner"), input = input(name = "Owner Cup", ownerUid = "owner")).shouldBeRight().domain()

        service.rename(token = token(uid = "other"), id = event.id, name = "Hijacked")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.toDomain().name shouldBe "Owner Cup"

        service.rename(token = token(uid = "admin"), id = event.id, name = "Admin Renamed").shouldBeRight()
        events.findById(id = event.id)!!.toDomain().name shouldBe "Admin Renamed"
    }

    @Test
    fun `a tournament requires a circuit that exists, while non-tournaments carry none (#525)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        // A tournament with no circuit is rejected.
        service
            .create(token = token(uid = "host"), input = input(type = EventType.TOURNAMENT))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // A tournament with an unknown circuit is rejected.
        service
            .create(token = token(uid = "host"), input = input(type = EventType.TOURNAMENT, circuitId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // A tournament with a valid circuit is created and carries it.
        val circuitId = seedCircuit(hostUid = "host")
        val created =
            service.create(token = token(uid = "host"), input = input(type = EventType.TOURNAMENT, circuitId = circuitId)).shouldBeRight()
        events.findById(id = UUID.fromString(created.id))!!.toDomain().circuitId shouldBe circuitId
    }

    @Test
    fun `an event is created with each type, defaulting to OPEN_PLAY (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), input = input(name = "Default")).shouldBeRight()
            .domain().type shouldBe EventType.OPEN_PLAY
        service.create(
            token = token(uid = "host"),
            input = input(name = "Casual", type = EventType.OPEN_PLAY),
        ).shouldBeRight().domain().type shouldBe EventType.OPEN_PLAY
        val tourney =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        name = "Tourney",
                        type = EventType.TOURNAMENT,
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight()
        tourney.type shouldBe "TOURNAMENT"
        // The type round-trips through persistence.
        events.findById(id = UUID.fromString(tourney.id))!!.toDomain().type shouldBe EventType.TOURNAMENT
    }

    @Test
    fun `a host finalizes their own event, setting state and writing an Activity Log entry (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        event.isFinalized.shouldBeFalse()

        val finalized = service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        finalized.isFinalized.shouldBeTrue()
        finalized.domain().finalizedBy shouldBe host.id
        val persisted = events.findById(id = event.id)!!.toDomain()
        persisted.isFinalized.shouldBeTrue()
        persisted.finalizedBy shouldBe host.id
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_FINALIZED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Finalized event Spring Open"
        }
    }

    @Test
    fun `finalize is terminal - a second finalize is rejected as Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.finalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a non-staff caller cannot finalize, and a host cannot finalize another host's event (#403)`() {
        val creator = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val clubOwner = provision(uid = "clubowner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        // The club's named owner is the club owner; the creating host owns it too so they can file under it.
        val club = ownedClub(name = "Downtown TC", owner = clubOwner)
        clubs.addOwner(clubId = club.id, userId = creator.id)
        val event = service.create(token = token(uid = "owner"), input = clubInput(clubId = club.id)).shouldBeRight().domain()

        service.finalize(token = token(uid = "ghost"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.finalize(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.toDomain().isFinalized.shouldBeFalse()

        // A named owner of the event's club may finalize an event they didn't create (#789).
        service.finalize(token = token(uid = "clubowner"), id = event.id).shouldBeRight().domain().isFinalized.shouldBeTrue()
    }

    @Test
    fun `an administrator may finalize any event (#403)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input(ownerUid = "owner")).shouldBeRight().domain()

        service.finalize(token = token(uid = "admin"), id = event.id).shouldBeRight().domain().isFinalized.shouldBeTrue()
    }

    @Test
    fun `finalizing a non-existent event is NotFound, and a deleted event is Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.finalize(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        // A soft-deleted (inactive) event cannot be finalized.
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.finalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a finalized event rejects rename, set-club, and participant mutations as Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.rename(token = token(uid = "host"), id = event.id, name = "New Name")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setClub(token = token(uid = "host"), id = event.id, clubId = fixtureClub(ownerUid = "host"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = player.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.decideParticipant(
            token = token(uid = "host"),
            eventId = event.id,
            userId = player.id,
            statusRaw = EventParticipantStatus.APPROVED.name,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    // --- Un-finalize (#477). ---

    @Test
    fun `a host un-finalizes their own event, clearing the flag and writing an Activity Log entry (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        val reopened = service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        reopened.isFinalized.shouldBeFalse()
        reopened.domain().finalizedBy.shouldBeNull()
        val persisted = events.findById(id = event.id)!!.toDomain()
        persisted.isFinalized.shouldBeFalse()
        persisted.finalizedBy.shouldBeNull()
        persisted.finalizedAt.shouldBeNull()
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_UNFINALIZED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.entityType shouldBe AuditEntityType.EVENT.name
            it.summary shouldContain "Un-finalized event Spring Open"
        }
    }

    @Test
    fun `an administrator may un-finalize any event, and a club owner too (#477)`() {
        val creator = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val clubOwner = provision(uid = "clubowner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val club = ownedClub(name = "Downtown TC", owner = clubOwner)
        clubs.addOwner(clubId = club.id, userId = creator.id)
        val event = service.create(token = token(uid = "owner"), input = clubInput(clubId = club.id)).shouldBeRight().domain()
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()

        service.unfinalize(token = token(uid = "admin"), id = event.id).shouldBeRight().domain().isFinalized.shouldBeFalse()

        // A named owner of the event's club may also un-finalize an event they didn't create (#789).
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()
        service.unfinalize(token = token(uid = "clubowner"), id = event.id).shouldBeRight().domain().isFinalized.shouldBeFalse()
    }

    @Test
    fun `a non-staff caller cannot un-finalize, and a host cannot un-finalize another host's event (#477)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "owner"), input = input(ownerUid = "owner")).shouldBeRight().domain()
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()

        service.unfinalize(token = token(uid = "ghost"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.unfinalize(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.toDomain().isFinalized.shouldBeTrue()
    }

    @Test
    fun `un-finalizing a non-finalized event is Validation, and a non-existent event is NotFound (#477)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        service.unfinalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
            .message shouldContain "not finalized"
        service.unfinalize(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `un-finalize is refused when any of the event's matches are already rated (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match =
            seedCompletedFixture(
                eventId = event.id,
                host = host,
                p1 = p1,
                p2 = p2,
                placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
            )
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        // The Administrator has since run the rating-calculation trigger on this match.
        matchRepo.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        val error =
            service.unfinalize(token = token(uid = "host"), id = event.id)
                .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        error.message shouldContain "already-rated"
        // The event stays finalized and its awards stay live — nothing was reversed (both placements).
        events.findById(id = event.id)!!.toDomain().isFinalized.shouldBeTrue()
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 2
    }

    @Test
    fun `un-finalize revokes every active award for the event, appending a revocation marker each (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        // A Super Finals placement match pays both players → two active awards.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 2

        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // No active awards remain for the event; each original flips to REVOKED and a marker row is appended.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 0
        val rows = awardRepo.listByUser(userId = p1.id)
        rows shouldHaveSize 2
        rows.count { it.status == AwardStatus.ACTIVE.name } shouldBe 0
        val marker = rows.single { it.revokesAwardId != null }
        marker.status shouldBe AwardStatus.REVOKED.name
        marker.points shouldBe BigDecimal("0.0000")
        marker.reason.shouldNotBeNull() shouldContain "Reversed on un-finalize"
        // The reversal is audited with the revoked count (both placement awards).
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_UNFINALIZED), limit = 10, offset = 0)
            .first.single().details["awardsRevoked"] shouldBe "2"
    }

    @Test
    fun `un-finalize then re-finalize is allowed, letting a corrected event award again (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Fresh ACTIVE awards exist after re-finalize (both placements); the earlier ones stay revoked.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 2
    }

    @Test
    fun `renaming a non-existent event is NotFound, and a non-staff caller is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.rename(token = token(uid = "host"), id = UUID.randomUUID(), name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.rename(token = token(uid = "ghost"), id = UUID.randomUUID(), name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `deleting a non-existent event is NotFound, and a non-staff caller is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service
            .delete(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service.delete(token = token(uid = "ghost"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a caller whose token maps to no account is forbidden`() {
        // The "ghost" token resolves to no user — the staff gate denies before any work, so the club id
        // never has to exist (nobody is provisioned here to own one).
        service
            .create(token = token(uid = "ghost"), input = input(clubId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a player self-signs-up as PENDING and isn't on the approved roster yet (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        val public = service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe "PENDING"
        // The public roster lists APPROVED members only — the pending request isn't shown there.
        public.participants.none { it.userId == player.id.toString() }.shouldBeTrue()
        // Signing up again is idempotent (still a single PENDING request).
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight().viewerStatus shouldBe "PENDING"

        // The organizer sees the request; the approved roster (fixtures/seeding) excludes it.
        val view = service.get(token = token(uid = "host"), id = event.id).shouldBeRight()
        view.participants.single { it.userId == player.id.toString() }.status shouldBe "PENDING"
        view.domain().participantIds.contains(element = player.id).shouldBeFalse()
    }

    @Test
    fun `a deleted account cannot self-sign-up for events (#518)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        // Soft-delete the player (is_active=false, canonical null → isDeleted()).
        UserRepository().deactivate(id = player.id).shouldBeRight()

        service
            .selfSignup(token = token(uid = "player"), code = event.publicCode)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a host approves a request, adding the player to the roster (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        val view =
            service
                .decideParticipant(
                    token = token(uid = "host"),
                    eventId = event.id,
                    userId = player.id,
                    statusRaw = EventParticipantStatus.APPROVED.name,
                ).shouldBeRight()
        view.participants.single { it.userId == player.id.toString() }.status shouldBe "APPROVED"
        view.domain().participantIds.contains(element = player.id).shouldBeTrue()

        val public = service.publicByCode(token = token(uid = "player"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe "APPROVED"
        public.participants.any { it.userId == player.id.toString() }.shouldBeTrue()
    }

    @Test
    fun `a host can hold a request and later approve it (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                statusRaw = EventParticipantStatus.HOLD.name,
            )
            .shouldBeRight()
            .participants
            .single { it.userId == player.id.toString() }
            .status shouldBe "HOLD"
        // On hold → still off the approved roster.
        service.get(
            token = token(uid = "host"),
            id = event.id,
        ).shouldBeRight().domain().participantIds.contains(element = player.id).shouldBeFalse()

        // A held request can later be approved.
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                statusRaw = EventParticipantStatus.APPROVED.name,
            ).shouldBeRight()
            .domain().participantIds
            .contains(element = player.id)
            .shouldBeTrue()
    }

    @Test
    fun `a non-staff caller cannot decide a request, and self-signup needs a profile (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        provision(uid = "outsider")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        service
            .decideParticipant(
                token = token(uid = "outsider"),
                eventId = event.id,
                userId = player.id,
                statusRaw = EventParticipantStatus.APPROVED.name,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        // An unprovisioned token can't self-sign-up.
        service.selfSignup(
            token = token(uid = "ghost"),
            code = event.publicCode,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `decideParticipant rejects an unknown decision status (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                statusRaw = "NOT_A_STATUS",
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a host-added participant is approved outright (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        val view = service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = p1.id).shouldBeRight()
        view.participants.single { it.userId == p1.id.toString() }.status shouldBe "APPROVED"
        view.domain().participantIds.contains(element = p1.id).shouldBeTrue()
    }

    @Test
    fun `a host-add promotes an existing pending request to APPROVED (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        val view = service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = player.id).shouldBeRight()
        view.participants.single { it.userId == player.id.toString() }.status shouldBe "APPROVED"
        view.domain().participantIds.contains(element = player.id).shouldBeTrue()
    }

    @Test
    fun `signup and decide report not-found and reject a non-decision status (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        // Unknown code / event id.
        service.selfSignup(token = token(uid = "player"), code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = UUID.randomUUID(),
                userId = player.id,
                statusRaw = EventParticipantStatus.APPROVED.name,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        // PENDING is not a valid decision (only APPROVED/HOLD).
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                statusRaw = EventParticipantStatus.PENDING.name,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // The repository self-signup is a no-op (null) for an unknown event.
        events.selfSignup(eventId = UUID.randomUUID(), userId = player.id) shouldBe null
    }

    @Test
    fun `the public event summary has no viewer status for an unprovisioned viewer (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        val public = service.publicByCode(token = token(uid = "ghost"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe null
    }

    @Test
    fun `the public event summary is viewable anonymously, with no viewer status (#193)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().domain()

        val public = service.publicByCode(token = null, code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe null
    }

    @Test
    fun `myEvents lists the caller's events with their standing, empty for an unprovisioned caller (#202)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val a = service.create(token = token(uid = "host"), input = input(name = "Alpha")).shouldBeRight().domain()
        val b = service.create(token = token(uid = "host"), input = input(name = "Bravo")).shouldBeRight().domain()
        service.selfSignup(token = token(uid = "player"), code = a.publicCode).shouldBeRight() // PENDING on A
        service.addParticipant(token = token(uid = "host"), eventId = b.id, userId = player.id).shouldBeRight() // APPROVED on B

        val mine = service.myEvents(token = token(uid = "player")).shouldBeRight()
        mine.map { it.publicCode to it.status }.toSet() shouldBe
            setOf(
                a.publicCode to "PENDING",
                b.publicCode to "APPROVED",
            )

        // An unprovisioned caller simply has no events.
        service.myEvents(token = token(uid = "ghost")).shouldBeRight() shouldBe emptyList()
    }

    @Test
    fun `completedResultCounts is batched and counts only recorded results per event (#483)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val withResults =
            service.create(
                token = token(uid = "host"),
                input = input(name = "WithResults", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().domain()
        val scheduledOnly =
            service.create(
                token = token(uid = "host"),
                input = input(name = "ScheduledOnly", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().domain()
        recordResult(match = seedFixture(eventId = withResults.id, host = host, p1 = p1, p2 = p2))
        // A scheduled fixture (no recorded result) must not lift the count off zero.
        seedFixture(eventId = scheduledOnly.id, host = host, p1 = p1, p2 = p2)

        val counts = service.completedResultCounts(eventIds = listOf(withResults.id, scheduledOnly.id))
        counts[withResults.id] shouldBe 1
        counts[scheduledOnly.id].shouldBeNull()
    }

    @Test
    fun `MyEventResponse carries isFinalized and completedMatchCount for the client's buckets (#483)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val created =
            service.create(
                token = token(uid = "host"),
                input = input(name = "Finalized", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().domain()
        recordResult(match = seedFixture(eventId = created.id, host = host, p1 = p1, p2 = p2))
        service.finalize(token = token(uid = "host"), id = created.id).shouldBeRight()

        // p1 is an approved participant, so the event shows on their "Events history".
        // myEvents now returns MyEventResponse DTOs directly, with isFinalized + completedMatchCount populated.
        val mine = service.myEvents(token = token(uid = "p1")).shouldBeRight().single { it.publicCode == created.publicCode }
        mine.isFinalized.shouldBeTrue()
        mine.completedMatchCount shouldBe 1
    }

    // --- Finalize-time awarding (#403 Phase D). ---

    private val awardRepo = RankingPointRepository()

    private fun budgetedEvent(
        hostUid: String,
        participants: List<UUID>,
        // TOURNAMENT exercises placement awarding (#525); OPEN_PLAY is computed per set (#553).
        type: EventType = EventType.TOURNAMENT,
        awardRankingPoints: Boolean = true,
    ): Event {
        // The global award flag (#641) gates awarding at create AND at finalize (#752), and defaults off,
        // so an event that is meant to pay out has to be created with it on.
        enableGlobalAwarding(hostUid = hostUid)
        return service.create(
            token = token(uid = hostUid),
            input =
                input(
                    // Ends today, so award validity (#559: from the event end) is active as of now — events
                    // are finalized once they have ended.
                    start = LocalDate.now().toString(),
                    end = LocalDate.now().toString(),
                    type = type,
                    participants = participants,
                    // A TOURNAMENT must belong to a circuit (#525); other types carry none.
                    circuitId = if (type == EventType.TOURNAMENT) seedCircuit(hostUid = hostUid) else null,
                    awardRankingPoints = awardRankingPoints,
                ),
        ).shouldBeRight().domain()
    }

    /** Turn the global "award ranking points" flag (#641) on, attributed to [hostUid] (#752). */
    private fun enableGlobalAwarding(hostUid: String) {
        val actor = requireNotNull(value = UserRepository().findByFirebaseUid(firebaseUid = hostUid)) { "unknown host $hostUid" }
        TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = actor.toDomain().id)
    }

    /** Seed a circuit (#525) attributed to [hostUid], returning its id — tournaments must reference one. */
    private fun seedCircuit(hostUid: String): UUID {
        val creator =
            requireNotNull(
                value = UserRepository().findByFirebaseUid(firebaseUid = hostUid),
            ) { "unknown host $hostUid" }.toDomain()
        return CircuitRepository().create(command = CreateCircuitCommand(name = "NORTH", createdBy = creator.id)).id
    }

    /**
     * Seed a COMPLETED singles fixture won by team1 (p1). [placementBracket] marks it a tournament
     * placement match (#525) — the only kind that pays tournament points.
     */
    private fun seedCompletedFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
        format: TeamType = TeamType.SINGLES,
        team1UserIds: List<UUID> = listOf(element = p1.id),
        team2UserIds: List<UUID> = listOf(element = p2.id),
        placementBracket: PlacementBracket? = null,
    ): Match {
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = format,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = team1UserIds,
                        team2UserIds = team2UserIds,
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                        isPlacementMatch = placementBracket != null,
                        placementBracket = placementBracket,
                    ),
            ).toDomain()
        recordResult(match = match)
        return matchRepo.findById(matchId = match.id).shouldBeRight().toDomain()
    }

    private fun rate(
        userId: UUID,
        level: String,
    ) = RatingRepository().setRating(userId = userId, rating = BigDecimal(level), level = level).also {
        settleAllRatings()
    }

    @Test
    fun `finalizing a TOURNAMENT event awards placement points from the sanction-selected table (#525)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // The event has no club, so the tournament is unsanctioned → 1st = 40, 2nd = 30.
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Super Finals: winner p1 → 1st (40), loser p2 → 2nd (30).
        val winner = awardRepo.listByUser(userId = p1.id).single()
        winner.points shouldBe placementRate(place = 1)
        winner.band shouldBe "4.0"
        winner.eventId shouldBe event.id
        winner.sourceId shouldBe event.id.toString()
        winner.pointClass shouldBe org.skopeo.domain.model.PointClass.ANNUAL_TOURNAMENT.name
        // Validity (#559) runs from the event end for the global tournament schedule's window (no per-event override).
        val tvDays = org.skopeo.domain.service.settings.PointsConfigService().getTournament().value.validityDays.toLong()
        winner.validFrom shouldBe event.endDate.atStartOfDay()
        winner.validUntil shouldBe event.endDate.plusDays(tvDays + 1).atStartOfDay()
        awardRepo.listByUser(userId = p2.id).single().points shouldBe placementRate(place = 2)
        // The award summary totals both placements (40 + 30).
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_POINTS_AWARDED), limit = 10, offset = 0)
            .first.single().details["totalPoints"] shouldBe
            TournamentPointsConfig.DEFAULT.unsanctioned.take(n = 2).sum().toString()
    }

    @Test
    fun `a semi-final is a non-placement fixture and earns per-set points, not a placing (#837)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        // A semi-final carries no bracket now: the former SEMI_FINALS_* options are gone (#837), so the
        // fixture falls into the per-set half of a tournament's payout (#836).
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The winner takes the open-play rate for the set — NOT the 3rd placement rate it used to pay —
        // and the loser gets a zero row at even bands rather than nothing at all.
        val winner = awardRepo.listByUser(userId = p1.id).single()
        winner.points shouldBe openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2)
        winner.points shouldNotBe placementRate(place = 3)
        awardRepo.listByUser(userId = p2.id).single().points shouldBe BigDecimal("0.0000")
    }

    @Test
    fun `without a Plate Finals no third or fourth place is awarded at all (#837)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        // Only a Championship Finals — the draw never played off for third.
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // A placing is only awarded where a fixture decided it, so 1st and 2nd exist and nothing else.
        val paid = awardRepo.listActiveByEvent(eventId = event.id).map { it.points }
        paid shouldContainExactlyInAnyOrder listOf(placementRate(place = 1), placementRate(place = 2))
        paid shouldNotContain placementRate(place = 3)
        paid shouldNotContain placementRate(place = 4)
    }

    @Test
    fun `a plate final decides 3rd and 4th, and the semi feeding it pays per-set instead (#837)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val p3 = provision(uid = "p3")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        rate(userId = p3.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id, p3.id))
        // Semi, unmarked: p1 beats p2 → p1 advances, p2 drops to the plate.
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        // Plate Finals: p2 beats p3 → p2 is 3rd, p3 is 4th.
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p2,
            p2 = p3,
            placementBracket = PlacementBracket.PLATE_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // p1 now earns per-set points for winning the semi, where before it paid them nothing.
        awardRepo.listByUser(userId = p1.id).single().points shouldBe
            openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2)
        // p2 holds two rows: 3rd from the plate, and a zero row for losing the semi.
        awardRepo.listByUser(userId = p2.id).map { it.points } shouldContainExactlyInAnyOrder
            listOf(placementRate(place = 3), BigDecimal("0.0000"))
        awardRepo.listByUser(userId = p3.id).single().points shouldBe placementRate(place = 4)
    }

    @Test
    fun `finalizing an event with Award Ranking Points off awards nothing (#559)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event =
            budgetedEvent(
                hostUid = "host",
                participants = listOf(p1.id, p2.id),
                type = EventType.OPEN_PLAY,
                awardRankingPoints = false,
            )
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // "Award Ranking Points" unchecked (#559): finalizing awards nothing, whatever the type.
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
        awardRepo.listByUser(userId = p2.id) shouldHaveSize 0
    }

    // --- Global "award ranking points" flag enforced server-side (#752). ---

    @Test
    fun `create coerces an award opt-in to false while the global flag is off, and audits it (#752)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        // The global flag defaults off, so this opt-in — a stale bundle, a partner client, or curl —
        // must not produce an event that awards.
        val created = service.create(token = token(uid = "host"), input = input(awardRankingPoints = true)).shouldBeRight()

        created.awardRankingPoints.shouldBeFalse()
        created.domain().awardRankingPoints.shouldBeFalse()
        // The coercion is spelled out in the Activity Log rather than applied silently.
        val entry =
            AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CREATED), limit = 10, offset = 0).first.single()
        entry.details["awardRankingPoints"] shouldBe "false"
        entry.details["awardRankingPointsCoercedByGlobalFlag"] shouldBe "true"
    }

    @Test
    fun `create keeps the award opt-in while the global flag is on (#752)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        enableGlobalAwarding(hostUid = "host")

        val created = service.create(token = token(uid = "host"), input = input(awardRankingPoints = true)).shouldBeRight()

        created.awardRankingPoints.shouldBeTrue()
        created.domain().awardRankingPoints.shouldBeTrue()
        val entry =
            AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CREATED), limit = 10, offset = 0).first.single()
        entry.details["awardRankingPoints"] shouldBe "true"
        entry.details["awardRankingPointsCoercedByGlobalFlag"] shouldBe "false"
    }

    @Test
    fun `create with the award opt-out is untouched by the global flag being off (#752)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        val created = service.create(token = token(uid = "host"), input = input(awardRankingPoints = false)).shouldBeRight()

        // Nothing to coerce: the organizer already opted out, so no coercion is reported.
        created.awardRankingPoints.shouldBeFalse()
        val entry =
            AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CREATED), limit = 10, offset = 0).first.single()
        entry.details["awardRankingPointsCoercedByGlobalFlag"] shouldBe "false"
    }

    @Test
    fun `turning the global flag off after create suppresses the payout at finalize, and says so (#752)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // Created while the flag was ON, so the event's own flag is set and stays set.
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )
        TestAppSettings.setAwardRankingPoints(enabled = false, updatedBy = host.id)

        val finalized = service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The kill switch: zero awards despite the event's own opt-in, and the suppression is reported.
        finalized.isFinalized.shouldBeTrue()
        finalized.awardingSuppressedByGlobalFlag.shouldBeTrue()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
        awardRepo.listByUser(userId = p2.id) shouldHaveSize 0
        val entry =
            AuditRepository().list(actions = listOf(element = AuditAction.EVENT_POINTS_AWARDED), limit = 10, offset = 0).first.single()
        entry.details["suppressedByGlobalFlag"] shouldBe "true"
        entry.details["awards"] shouldBe "0"
        entry.summary shouldContain "disabled by the global flag"
    }

    @Test
    fun `finalizing with the global flag on reports no suppression and still pays out (#752)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        val finalized = service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        finalized.awardingSuppressedByGlobalFlag.shouldBeFalse()
        awardRepo.listByUser(userId = p1.id).single().points shouldBe placementRate(place = 1)
        AuditRepository()
            .list(actions = listOf(element = AuditAction.EVENT_POINTS_AWARDED), limit = 10, offset = 0)
            .first.single().details["suppressedByGlobalFlag"] shouldBe "false"
    }

    @Test
    fun `a per-event opt-out is not reported as a global-flag suppression (#752)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // Global flag on, per-event flag off (#559): nothing is awarded, but the global flag is blameless.
        val event =
            budgetedEvent(
                hostUid = "host",
                participants = listOf(p1.id, p2.id),
                awardRankingPoints = false,
            )
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        val finalized = service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        finalized.awardingSuppressedByGlobalFlag.shouldBeFalse()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `finalize records one per-player audit entry targeting each awarded player (#471)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        // A Super Finals placement match pays both sides: winner p1 → 1st (40), loser p2 → 2nd (30).
        val match =
            seedCompletedFixture(
                eventId = event.id,
                host = host,
                p1 = p1,
                p2 = p2,
                placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
            )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // One RANKING_POINTS_AWARDED entry per awarded player, each targeting the player (USER), with the
        // finalizer as actor and the points/match in the detail (#471).
        val entries =
            AuditRepository()
                .list(actions = listOf(element = AuditAction.RANKING_POINTS_AWARDED), limit = 10, offset = 0)
                .first
        entries shouldHaveSize 2
        entries.forEach { it.entityType shouldBe AuditEntityType.USER.name }
        entries.forEach { it.actorUserId shouldBe host.id }
        entries.map { it.entityId }.toSet() shouldBe setOf(p1.id, p2.id)

        val p1Entry = entries.single { it.entityId == p1.id }
        p1Entry.details["points"] shouldBe TournamentPointsConfig.DEFAULT.unsanctioned.first().toString()
        p1Entry.details["matchId"] shouldBe match.id.toString()
        p1Entry.details["matchPublicCode"] shouldBe match.publicCode
        p1Entry.details["eventId"] shouldBe event.id.toString()
        p1Entry.details["band"] shouldBe "4.0"

        val p2Entry = entries.single { it.entityId == p2.id }
        p2Entry.details["points"] shouldBe TournamentPointsConfig.DEFAULT.unsanctioned[1].toString()
        p2Entry.details["matchId"] shouldBe match.id.toString()
    }

    @Test
    fun `finalize records the granting match id on each award, for the profile points audit (#448)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match =
            seedCompletedFixture(
                eventId = event.id,
                host = host,
                p1 = p1,
                p2 = p2,
                placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
            )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The winner's award is tagged with the exact placement match that granted it (#448) — the audit
        // links straight to that match — while still carrying the event link for the fallback.
        val award = awardRepo.listByUser(userId = p1.id).single()
        award.matchId shouldBe match.id
        award.eventId shouldBe event.id
        // And the active-awards read surfaces it.
        awardRepo.listActiveByUser(userId = p1.id, asOf = LocalDateTime.now()).single().matchId shouldBe match.id
    }

    @Test
    fun `finalizing an OPEN_PLAY event awards computed per-set points to winner and loser (#525)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // Equal entry bands (both 4.0): the set winner takes the margin base, the loser 0.
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input = input(type = EventType.OPEN_PLAY, participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().domain()
        // Open play does not designate points — the amount is computed from the result.
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Equal bands, and the seeded fixture is a margin-2 set, so the winner takes that cell's base;
        // loser p2 still gets a 0-point row, because both participants are recorded.
        val winner = awardRepo.listByUser(userId = p1.id).single()
        winner.points shouldBe openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2)
        winner.band shouldBe "4.0"
        winner.eventId shouldBe event.id
        winner.pointClass shouldBe org.skopeo.domain.model.PointClass.OPEN_PLAY.name
        awardRepo.listByUser(userId = p2.id).single().points shouldBe BigDecimal("0.0000")
        // Validity defaults to the schedule's window when the event configures none (#525): the stored
        // range is half-open, so validUntil sits one day past [validityDays] after the event end.
        winner.validFrom shouldBe event.endDate.atStartOfDay()
        val openPlayDays = OpenPlayPointsConfig.DEFAULT.validityDays.toLong()
        winner.validUntil shouldBe event.endDate.plusDays(openPlayDays + 1).atStartOfDay()
    }

    @Test
    fun `finalizing a doubles fixture writes one full-amount row per winning-team member (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "a")
        val b = provision(uid = "b")
        val c = provision(uid = "c")
        val d = provision(uid = "d")
        listOf(a, b, c, d).forEach { rate(userId = it.id, level = "3.5") }
        val event = budgetedEvent(hostUid = "host", participants = listOf(a.id, b.id, c.id, d.id), type = EventType.TOURNAMENT)
        // Doubles Super Finals: winning pair (a, b) each place 1st (40); losing pair (c, d) each place 2nd (30).
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = a,
            p2 = c,
            format = TeamType.DOUBLES,
            team1UserIds = listOf(a.id, b.id),
            team2UserIds = listOf(c.id, d.id),
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Both winning-team members get the FULL 1st-place points (two rows of 40, not a split).
        awardRepo.listByUser(userId = a.id).single().points shouldBe placementRate(place = 1)
        awardRepo.listByUser(userId = b.id).single().points shouldBe placementRate(place = 1)
        // Both losing-team members get 2nd-place points.
        awardRepo.listByUser(userId = c.id).single().points shouldBe placementRate(place = 2)
        awardRepo.listByUser(userId = d.id).single().points shouldBe placementRate(place = 2)
    }

    @Test
    fun `finalizing an event whose fixtures have no designated or no winner awards nothing (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        // A completed fixture with no designation → nothing awarded (clubless events skip designation here).
        val open = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().domain()
        seedCompletedFixture(eventId = open.id, host = host, p1 = p1, p2 = p2)
        service.finalize(token = token(uid = "host"), id = open.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0

        // A budgeted event whose only fixture is still SCHEDULED (no winner) → nothing awarded.
        val league = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        matchRepo.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(element = p1.id),
                    team2UserIds = listOf(element = p2.id),
                    team1Name = "t1",
                    team2Name = "t2",
                    createdBy = host.id,
                    eventId = league.id,
                ),
        )
        service.finalize(token = token(uid = "host"), id = league.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `a winner with no rating is skipped and not awarded (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // p1 wins but has no rating → no band to tag → the award is skipped.
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `a completed fixture with no designation in a budgeted event awards nothing (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        // A budgeted event whose completed, won fixture carries NO designation → the designation filter
        // drops it (exercises the null-designation arm without an early type/config return).
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `placement awards use the ANNUAL_TOURNAMENT point class regardless of the event window (#525)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        fun finalizePlacement(
            p1uid: String,
            p2uid: String,
        ): org.skopeo.domain.model.PointClass {
            val p1 = provision(uid = p1uid)
            val p2 = provision(uid = p2uid)
            rate(userId = p1.id, level = "4.0")
            rate(userId = p2.id, level = "4.0")
            val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id), type = EventType.TOURNAMENT)
            seedCompletedFixture(
                eventId = event.id,
                host = host,
                p1 = p1,
                p2 = p2,
                placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
            )
            service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
            return awardRepo.listByUser(userId = p1.id).single().toDomain().pointClass
        }

        // Placement points always carry ANNUAL_TOURNAMENT (#525) — the old window→class mapping is gone.
        finalizePlacement(p1uid = "a1", p2uid = "a2") shouldBe org.skopeo.domain.model.PointClass.ANNUAL_TOURNAMENT
        finalizePlacement(p1uid = "d1", p2uid = "d2") shouldBe org.skopeo.domain.model.PointClass.ANNUAL_TOURNAMENT
    }

    @Test
    fun `the second team's win path is awarded when team2 is the winner (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = event.id,
                        isPlacementMatch = true,
                        placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
                    ),
            ).toDomain()
        // team2 (p2) wins the Super Finals → p2 places 1st (40), the team1 loser places 2nd (30).
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 4, team2Games = 6, winnerTeamId = match.team2.teamId)),
            winnerTeamId = match.team2.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p2.id).single().points shouldBe placementRate(place = 1)
        awardRepo.listByUser(userId = p1.id).single().points shouldBe placementRate(place = 2)
    }

    @Test
    fun `a sanctioned club's tournament awards the full placement table (#525)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val club = ownedClub(name = "Downtown TC", owner = host)
        clubs.setSanction(id = club.id, sanctioned = true)
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.TOURNAMENT,
                        participants = listOf(p1.id, p2.id),
                        clubId = club.id,
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight().domain()
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Sanctioned Super Finals: winner p1 → 1st (80), loser p2 → 2nd (60).
        awardRepo.listByUser(userId = p1.id).single().points shouldBe placementRate(place = 1, sanctioned = true)
        awardRepo.listByUser(userId = p2.id).single().points shouldBe placementRate(place = 2, sanctioned = true)
    }

    @Test
    fun `a Plate Finals awards 3rd and 4th place, and an unrated placer is skipped (#525)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // p1 (winner) is rated → 3rd place; p2 (loser) has no rating → no band → skipped.
        rate(userId = p1.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.PLATE_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Unsanctioned Plate Finals: winner p1 → 3rd (20); the unrated loser p2 is skipped.
        awardRepo.listByUser(userId = p1.id).single().points shouldBe placementRate(place = 3)
        awardRepo.listByUser(userId = p2.id) shouldHaveSize 0
    }

    @Test
    fun `create stores the event organizing format (#720)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        val view = service.create(token = token(uid = "host"), input = input(format = "DOUBLES")).shouldBeRight()

        view.format shouldBe "DOUBLES"
        view.domain().format shouldBe TeamType.DOUBLES
    }

    @Test
    fun `create rejects an invalid organizing format (#720)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        val error = service.create(token = token(uid = "host"), input = input(format = "NOPE")).shouldBeLeft()

        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "Invalid format"
    }

    @Test
    fun `a tournament pays per-set points for non-placement fixtures on the tournament validity window (#836)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.TOURNAMENT,
                        participants = listOf(p1.id, p2.id),
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight().domain()
        // No placementBracket: an elimination round, which paid nothing before #836.
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Amount comes from the OPEN-PLAY schedule (equal bands, margin-2 set)...
        val winner = awardRepo.listByUser(userId = p1.id).single()
        winner.points shouldBe openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2)
        awardRepo.listByUser(userId = p2.id).single().points shouldBe BigDecimal("0.0000")
        // ...but the class and window come from the TOURNAMENT schedule, not open play's. This split is
        // the whole point of #836: the schedule decides the amount, the event decides how long it lasts.
        winner.pointClass shouldBe org.skopeo.domain.model.PointClass.ANNUAL_TOURNAMENT.name
        val tournamentDays = TournamentPointsConfig.DEFAULT.validityDays.toLong()
        winner.validFrom shouldBe event.endDate.atStartOfDay()
        winner.validUntil shouldBe event.endDate.plusDays(tournamentDays + 1).atStartOfDay()
        // Explicitly NOT the open-play window, which is much shorter.
        val openPlayDays = OpenPlayPointsConfig.DEFAULT.validityDays.toLong()
        (tournamentDays > openPlayDays) shouldBe true
    }

    @Test
    fun `a placement fixture pays its placement only, never per-set points as well (#836)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.TOURNAMENT,
                        participants = listOf(p1.id, p2.id),
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight().domain()
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Exactly one row each — the placement rate — and no second per-set row for the same fixture.
        awardRepo.listByUser(userId = p1.id).single().points shouldBe placementRate(place = 1)
        awardRepo.listByUser(userId = p2.id).single().points shouldBe placementRate(place = 2)
    }

    @Test
    fun `a tournament pays placement and per-set points side by side (#836)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.TOURNAMENT,
                        participants = listOf(p1.id, p2.id),
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight().domain()
        // A round-robin fixture and the final, same two players.
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = p1,
            p2 = p2,
            placementBracket = PlacementBracket.CHAMPIONSHIP_FINALS,
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // p1 holds two rows: the 1st-place rate for the final, and per-set points for the round fixture.
        val p1Points = awardRepo.listByUser(userId = p1.id).map { it.points }
        p1Points shouldContainExactlyInAnyOrder
            listOf(placementRate(place = 1), openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2))
        // p2 holds the 2nd-place rate plus a zero row for losing the round fixture at even bands.
        awardRepo.listByUser(userId = p2.id).map { it.points } shouldContainExactlyInAnyOrder
            listOf(placementRate(place = 2), BigDecimal("0.0000"))
    }

    @Test
    fun `unfinalizing a tournament revokes its per-set awards as well as its placements (#836)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.TOURNAMENT,
                        participants = listOf(p1.id, p2.id),
                        circuitId = seedCircuit(hostUid = "host"),
                    ),
            ).shouldBeRight().domain()
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 2

        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The reverser is event-scoped, so the new per-set rows are covered without special handling.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 0
    }

    @Test
    fun `a FULL_MATCH event pays open-play amounts on the Full Match window and class (#840)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        enableGlobalAwarding(hostUid = "host")
        val event =
            service.create(
                token = token(uid = "host"),
                input = input(type = EventType.FULL_MATCH, participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().domain()
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        val winner = awardRepo.listByUser(userId = p1.id).single()
        // The AMOUNT is the open-play schedule's — there is deliberately no second table (#840).
        winner.points shouldBe openPlayWinnerRate(relation = BandRelation.EQUAL, margin = 2)
        awardRepo.listByUser(userId = p2.id).single().points shouldBe BigDecimal("0.0000")
        // The CLASS and WINDOW are Full Match's, so the two are separable in the ledger and the window
        // can be tuned on its own. This split is the crux of the type; assert both halves of it.
        winner.pointClass shouldBe org.skopeo.domain.model.PointClass.FULL_MATCH.name
        val fullMatchDays = FullMatchPointsConfig.DEFAULT.validityDays.toLong()
        winner.validFrom shouldBe event.endDate.atStartOfDay()
        winner.validUntil shouldBe event.endDate.plusDays(fullMatchDays + 1).atStartOfDay()
        // Explicitly NOT the open-play window, even though the amounts came from that schedule.
        val openPlayDays = OpenPlayPointsConfig.DEFAULT.validityDays.toLong()
        winner.validUntil shouldNotBe event.endDate.plusDays(openPlayDays + 1).atStartOfDay()
    }

    /**
     * The default placement rate for [place] (1-based) at the ledger's scale. Derived from the shipped
     * schedule rather than hard-coded, so a schedule change is a single edit in
     * `PointsConfigContractTest` — these tests assert the *awarding* logic (right place, right table),
     * not the values.
     */
    private fun placementRate(
        place: Int,
        sanctioned: Boolean = false,
    ): BigDecimal = BigDecimal(TournamentPointsConfig.DEFAULT.schedule(sanctioned = sanctioned)[place - 1]).setScale(4)

    /** The default open-play winner rate for a [relation] set won by [margin] games, at the ledger's scale. */
    private fun openPlayWinnerRate(
        relation: BandRelation,
        margin: Int,
    ): BigDecimal = BigDecimal(OpenPlayPointsConfig.DEFAULT.cell(relation = relation, margin = margin).winnerPoints).setScale(4)
}
