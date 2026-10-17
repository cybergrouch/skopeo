// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.match.MatchReplayBackfillRequest
import org.skopeo.domain.service.livematch.MatchReplayService

/**
 * The permanent replay of a live-scored match (#1145).
 *
 * The read is public and anonymous, like the match page it sits on: it is addressed by the match's public
 * code, and a 404 means "no replay to show" — never scored live, or the result has since been edited away
 * from the one the replay ends at.
 *
 * **Optional auth** (#1183), like the match points read: a token is used when supplied but never
 * required. It decides only whether the per-point scorers are included, which the service omits from the
 * payload for a caller not entitled to them.
 */
fun Application.configureMatchReplayRoutes(service: MatchReplayService = MatchReplayService()) {
    routing {
        authenticate(FIREBASE_AUTH, optional = true) {
            get(path = "/api/v1/matches/code/{code}/replay") {
                respondMappingErrors {
                    val code = call.parameters.getOrFail(name = "code")
                    respondEither(result = service.byCode(code = code, token = optionalVerifiedToken())) { replay ->
                        call.respond(status = HttpStatusCode.OK, message = replay)
                    }
                }
            }
        }
        authenticate(FIREBASE_AUTH) {
            // ADMINISTRATOR only, dry run by default — mirrors the live-score sweep it protects.
            post(path = "/api/v1/match-replays/backfill") {
                respondMappingErrors {
                    val request =
                        runCatching { call.receiveNullable<MatchReplayBackfillRequest>() }.getOrNull()
                            ?: MatchReplayBackfillRequest()
                    respondEither(result = service.backfill(token = verifiedToken(), request = request)) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome)
                    }
                }
            }
        }
    }
}
