// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.domain.service.livematch.LiveMatchService

/**
 * Live scoring for a match (#911) — the umpire's surface.
 *
 * Nested under the match because a live score has no identity of its own: it is the match, as it stands
 * right now. Authorization is a flat `SCORING_ROLES` check inside [LiveMatchService], deliberately
 * outside the #789 per-club gate every other event-scoped operation goes through — an umpire pool moves
 * between clubs.
 *
 * The read is authenticated for now. It is the umpire's and organizer's view; the *public* spectator
 * scoreboard is a Firestore projection written by the server, not this endpoint.
 */
fun Application.configureLiveMatchRoutes(service: LiveMatchService = LiveMatchService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/matches/{matchId}/live") {
                readScore(service = service)
                recordEvent(service = service)
                undo(service = service)
                claim(service = service)
                finalize(service = service)
            }
        }
    }
}

private fun Route.readScore(service: LiveMatchService) {
    get {
        respondMappingErrors {
            call.respond(status = HttpStatusCode.OK, message = service.scoreboard(matchId = uuidParam(name = "matchId")))
        }
    }
}

private fun Route.recordEvent(service: LiveMatchService) {
    post(path = "/events") {
        respondMappingErrors {
            val request = call.receive<LiveScoreEventRequest>()
            respondEither(
                result = service.record(token = verifiedToken(), matchId = uuidParam(name = "matchId"), request = request),
            ) { view ->
                call.respond(status = HttpStatusCode.Created, message = view)
            }
        }
    }
}

/**
 * Undo has its own endpoint rather than being a postable kind, because **the server picks the target**.
 *
 * Only the engine knows which action is still in force — a redo can bring an earlier one back — so a
 * client naming a sequence would be duplicating that reasoning and would drift from it. The body is
 * empty on purpose.
 */
private fun Route.undo(service: LiveMatchService) {
    post(path = "/undo") {
        respondMappingErrors {
            respondEither(result = service.undo(token = verifiedToken(), matchId = uuidParam(name = "matchId"))) { view ->
                call.respond(status = HttpStatusCode.OK, message = view)
            }
        }
    }
}

/**
 * Take or give up the scoring of a match.
 *
 * The claim is **soft**: POST always succeeds for an authorized scorer and displaces whoever held it.
 * There is no 409 to handle, because a lock that could refuse would strand a match behind a dead phone
 * without making the data any safer — the log's unique sequence constraint is what protects it.
 */
private fun Route.claim(service: LiveMatchService) {
    post(path = "/claim") {
        respondMappingErrors {
            respondEither(result = service.claim(token = verifiedToken(), matchId = uuidParam(name = "matchId"))) { view ->
                call.respond(status = HttpStatusCode.OK, message = view)
            }
        }
    }
    delete(path = "/claim") {
        respondMappingErrors {
            respondEither(result = service.release(token = verifiedToken(), matchId = uuidParam(name = "matchId"))) { view ->
                call.respond(status = HttpStatusCode.OK, message = view)
            }
        }
    }
}

/**
 * Write the live score into the match as a real result.
 *
 * Returns a `MatchResponse`, not a live view: after this the match IS the record, and handing back a
 * live scoreboard would suggest the log is still the answer to "what was the score". It is not (§8a).
 */
private fun Route.finalize(service: LiveMatchService) {
    post(path = "/finalize") {
        respondMappingErrors {
            respondEither(result = service.finalize(token = verifiedToken(), matchId = uuidParam(name = "matchId"))) { match ->
                call.respond(status = HttpStatusCode.OK, message = match)
            }
        }
    }
}
