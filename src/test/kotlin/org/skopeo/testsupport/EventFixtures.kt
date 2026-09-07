// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingCalculationService
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventsTable
import org.skopeo.repository.UserRepository
import org.skopeo.repository.UsersTable
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * An event that merely *exists*, for suites whose subject is a **match** rather than an event.
 *
 * Since #898 every `matches` row needs an event, exactly as #794 made every `events` row need a club —
 * so this is [seedClub]'s counterpart one level down, and it composes with it. Suites that drive
 * [org.skopeo.repository.MatchRepository] or build a [org.skopeo.domain.model.CreateFixtureCommand]
 * directly need an event id to hand it, and almost none of them care which event it is.
 *
 * Authorization is deliberately *not* arranged here. These suites bypass it, and an event with no
 * organizer relationship is the honest fixture for that: `events.created_by` is provenance, not
 * permission. A test that goes through `mayOrganize`/`ensureHostMayEnter` should build its event
 * explicitly — via [seedFixtureClub] and the service — so the authorization under test stays visible in
 * the test rather than hidden behind a helper.
 *
 * [participantIds] matters only for suites that then create fixtures through `MatchService`, which
 * checks both sides are participants (#138). Repository-level suites can leave it empty.
 */
fun seedEvent(
    name: String = "Fixture Event",
    participantIds: List<UUID> = emptyList(),
    startDate: LocalDate = LocalDate.of(2026, 1, 1),
    endDate: LocalDate = LocalDate.of(2026, 12, 31),
    clubId: UUID? = null,
): Event =
    EventRepository()
        .create(
            command =
                CreateEventCommand(
                    name = name,
                    startDate = startDate,
                    endDate = endDate,
                    participantIds = participantIds,
                    createdBy = anyEventCreatorId(),
                    clubId = clubId ?: seedClub(name = "$name Club").id,
                ),
        ).toDomain()

/** Convenience for the common case: a caller that needs only the id to hang a fixture off. */
fun seedEventId(name: String = "Fixture Event"): UUID = seedEvent(name = name).id

/**
 * Any user id at all, purely to satisfy the non-null `events.created_by` column — the same trick
 * [seedClub] uses for `clubs.created_by`. Prefers a user the suite already provisioned so this adds no
 * row a test might count, and provisions a throwaway only when the table is still empty.
 */
private fun anyEventCreatorId(): UUID =
    transaction { UsersTable.selectAll().map { it[UsersTable.id].value }.firstOrNull() }
        ?: UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "fixture-event-creator".asRedactable(),
                        identity =
                            UserIdentity(
                                provider = AuthProvider.PASSWORD,
                                providerUid = "fixture-event-creator",
                                isPrimary = true,
                            ),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Fixture Event Creator")),
                    ),
            ).toDomain()
            .id

/** Name of the per-suite shared event created by [fixtureEventId]. */
private const val SHARED_FIXTURE_EVENT = "Shared Fixture Event"

/**
 * The id of a single shared event for the current test, creating it on first use.
 *
 * This is the helper most suites want after #898. Handing every match its **own** event would be
 * wrong twice over: `sortForCalculation` keys on the event first, and a fresh event carries the same
 * default end date as every other, so the comparator would fall through to a random `eventId` UUID and
 * make processing order nondeterministic. One shared event keeps matches ordered by date exactly as
 * they were when they were eventless, so ordering-sensitive suites keep their existing expectations.
 *
 * It is resolved by name rather than cached in a field, which makes it **self-healing across
 * `PostgresTestDatabase.truncate()`**: after a truncation the lookup misses and a new event is created,
 * so no suite has to remember to reset anything in `@BeforeEach`.
 *
 * [participantIds] are added as APPROVED participants (idempotent), which is what
 * `MatchService.createFixture` requires of both sides (#138). Repository-level suites that bypass the
 * service can call this with none.
 */
fun fixtureEventId(vararg participantIds: UUID): UUID {
    val events = EventRepository()
    val existing =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.name eq SHARED_FIXTURE_EVENT }
                .firstOrNull()
                ?.get(EventsTable.id)
                ?.value
        }
    val id = existing ?: seedEvent(name = SHARED_FIXTURE_EVENT).id
    // Only real users: a suite may deliberately pass an unprovisioned id to exercise validation, and
    // event_participants has a foreign key to users — adding it would fail the insert, not the assertion.
    val known = transaction { UsersTable.selectAll().map { it[UsersTable.id].value }.toSet() }
    participantIds.filter { it in known }.forEach { events.addParticipant(eventId = id, userId = it, approvedBy = it) }
    return id
}

/**
 * [fixtureEventId] for a fixture's two sides: the shared event, with every named player registered.
 *
 * `MatchService.createFixture` rejects a fixture whose players are not participants of its event
 * (#138), so a service-level suite has to arrange that as well as supply the id. This does both, which
 * keeps the call site to the one line it was before #898 made `eventId` required.
 */
fun fixtureEventFor(
    team1: List<UUID>,
    team2: List<UUID>,
): UUID = fixtureEventId(*(team1 + team2).toTypedArray())

/**
 * [fixtureEventFor] for the DTO layer, where a fixture's sides and its event id are all id *strings*
 * (`CreateFixtureRequest`). Distinct name rather than an overload: `List<UUID>` and `List<String>`
 * erase to the same JVM signature.
 */
fun fixtureEventForRequest(
    team1: List<String>,
    team2: List<String>,
): String =
    fixtureEventFor(
        team1 = team1.mapNotNull { it.toUuidOrNull() },
        team2 = team2.mapNotNull { it.toUuidOrNull() },
    ).toString()

/**
 * Route-shape suites deliberately post malformed ids ("not-a-uuid") to assert a 400. Those never name a
 * real participant, so parsing must not throw here — the assertion under test is the route's, not ours.
 */
private fun String.toUuidOrNull(): UUID? =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Finalize the shared fixture event, so its matches become eligible for rating. Idempotent.
 *
 * #403 queues a completed match for rating only once its event is finalized, and it carved out
 * event-less matches so they queued immediately. #898 removed event-less matches, and with them that
 * carve-out — **finalizing the event is now the only route to a rating**. Suites that used to create a
 * bare match and rate it must therefore finalize in between, exactly as the product now requires.
 *
 * It cannot be folded into [fixtureEventId]: finalize is terminal (`ensureEventNotFinalized`), so an
 * event finalized at creation would refuse the very fixtures and results the suite is about to add.
 */
fun finalizeFixtureEvent() {
    val id =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.name eq SHARED_FIXTURE_EVENT }
                .firstOrNull()
                ?.get(EventsTable.id)
                ?.value
        } ?: return
    EventRepository().finalize(id = id, finalizedAt = LocalDateTime.now(), finalizedBy = anyEventCreatorId())
}

/**
 * [finalizeFixtureEvent] as a fluent step before triggering a calculation, for the many suites whose
 * matches live on the shared event: `calc.afterFinalizingFixtureEvent().calculate(...)`.
 */
fun RatingCalculationService.afterFinalizingFixtureEvent(): RatingCalculationService = also { finalizeFixtureEvent() }
