// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.ranking.RankingPointService

// The event routes addressed by an event's shareable PUBLIC CODE rather than its id. `/events/{code}`
// is the single event view for every audience (#741), so these back one page: the anonymous event read
// (#193 — no login required), the staff manager payload, and self-signup.
// Wired into `/api/v1/events` by configureEventRoutes; authz is enforced in the services.

/**
 * Public event page lookup by code (#138), viewable anonymously (#193) — a token only personalizes
 * the viewer status. The literal `code` segment matches before `/{id}`, so it never collides with the
 * UUID route.
 */
internal fun Route.publicEventByCode(service: EventService) {
    get(path = "/code/{code}") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.publicByCode(token = optionalVerifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}

/**
 * What an event awarded, per player (#857) — the public event page's points card.
 *
 * Anonymous, like the event read it sits beside: amounts are already public (a points total is public
 * under the POINTS standings source; rank and band are public per #64/#114). It carries **no derivation**,
 * which is gated separately (#858), so there is nothing here to withhold from a stranger.
 *
 * Lives with the other by-code event routes rather than under `/ranking-points`, because the resource
 * being addressed is the event; the summing is delegated to [RankingPointService], which owns award reads.
 */
internal fun Route.eventPointsByCode(points: RankingPointService) {
    get(path = "/code/{code}/points") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = points.awardedForEvent(code = code)) { summary ->
                call.respond(status = HttpStatusCode.OK, message = summary)
            }
        }
    }
}

/**
 * The manager payload for an event addressed by its public code (#741). `/events/{code}` is the single
 * event view for every audience, so a staff viewer loads the organizer fields — and the event id every
 * mutation route is keyed by — from here, alongside the public read. Same staff gating as `GET /{id}`.
 */
internal fun Route.manageEventByCode(service: EventService) {
    get(path = "/code/{code}/manage") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.manageByCode(token = verifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}

/** Self-signup (#201): any authenticated player requests to join the event by its public code. */
internal fun Route.eventSelfSignup(service: EventService) {
    post(path = "/code/{code}/signup") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.selfSignup(token = verifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}
