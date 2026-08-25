// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.club

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import org.skopeo.common.dto.club.ClubPublicEventsResponse
import org.skopeo.common.dto.club.ClubPublicResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.dto.club.toDto
import org.skopeo.domain.mapper.dto.club.toResponse
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.model.Club
import org.skopeo.domain.model.ClubPublicEvent
import org.skopeo.domain.model.ClubPublicView
import org.skopeo.domain.model.EventBucket
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import java.time.LocalDate

// Page-size bounds for the paginated public event listing (#786), matching the other paginated reads.
private const val MIN_PAGE_SIZE = 1
private const val MAX_PAGE_SIZE = 100

/**
 * The anonymous reads behind a club's public page (#327/#786): the club summary, and one page of its
 * events in a single bucket.
 *
 * A small collaborator rather than more surface on [ClubService], which owns the authenticated club
 * administration — mirroring how the events package keeps `EventFinalizeAwarder` and
 * `EventRatingsReverser` beside `EventService`. Nothing here takes a token: these are public.
 */
class ClubPublicReader(
    private val clubs: ClubRepository = ClubRepository(),
    private val events: EventRepository = EventRepository(),
    private val matches: MatchRepository = MatchRepository(),
) {
    /**
     * Read-only public summary of a club by its shareable code (#327). Viewable by anyone (anonymous
     * included, like the event/match/player public pages): the club's name and code, nothing else. Its
     * events are fetched separately and per bucket by [publicEventsByCode] (#786), so rendering the header
     * no longer costs a full scan of everything the club has ever run.
     */
    fun publicByCode(code: String): Either<ServiceError, ClubPublicResponse> =
        either {
            val club = findPublicClub(code = code).bind()
            ClubPublicView(publicCode = club.publicCode, name = club.name, isActive = club.isActive).toResponse()
        }

    /**
     * One page of a club's events in a single bucket (#786), for the public club page's paginated cards.
     *
     * The bucket is evaluated in SQL (see [EventRepository.listByClubAndBucket]) so this fetches and counts
     * only the page asked for. [limit] is coerced into 1..100 and [offset] to at least 0, matching the
     * other paginated read endpoints, so a hand-rolled query string can't ask for everything.
     */
    fun publicEventsByCode(
        code: String,
        bucket: String?,
        limit: Int,
        offset: Int,
    ): Either<ServiceError, ClubPublicEventsResponse> =
        either {
            // Input parsing lives here, not at the route: `routes` must not depend on `model`
            // (LayeredArchitectureTest), and an unknown bucket is a business validation, not a shape error.
            val parsed =
                ensureNotNull(value = EventBucket.entries.firstOrNull { it.name == bucket }) {
                    ServiceError.Validation(
                        message = "Invalid bucket '$bucket'; expected one of ${EventBucket.entries.joinToString { it.name }}",
                    )
                }
            val club = findPublicClub(code = code).bind()
            val (page, total) =
                events.listByClubAndBucket(
                    clubId = club.id,
                    bucket = parsed,
                    today = LocalDate.now(),
                    limit = limit.coerceIn(range = MIN_PAGE_SIZE..MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                )
            // The "has results" signal is already implicit in the bucket, but the DTO reports it so a client
            // can label a row without a second call.
            val domainEvents = page.map { it.toDomain() }
            val counts = matches.completedResultCountByEvents(eventIds = domainEvents.map { it.id })
            ClubPublicEventsResponse(
                bucket = parsed.name,
                items =
                    domainEvents.map { event ->
                        ClubPublicEvent(
                            publicCode = event.publicCode,
                            name = event.name,
                            startDate = event.startDate,
                            endDate = event.endDate,
                            eventType = event.type,
                            isFinalized = event.isFinalized,
                            finalizedAt = event.finalizedAt,
                            completedMatchCount = counts[event.id] ?: 0,
                        ).toDto()
                    },
                total = total,
            )
        }

    /**
     * Resolve a public club by its shareable code, or [ServiceError.NotFound]. Shared by the two anonymous
     * reads: the summary and the paginated event listing.
     */
    private fun findPublicClub(code: String): Either<ServiceError, Club> =
        either {
            ensureNotNull(value = clubs.findByPublicCode(code = code)) {
                ServiceError.NotFound(message = "Club $code not found")
            }.toDomain()
        }
}
