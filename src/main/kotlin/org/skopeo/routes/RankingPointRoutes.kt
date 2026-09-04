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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.ranking.AdjustRankingPointsRequest
import org.skopeo.common.dto.ranking.GrantRankingPointsRequest
import org.skopeo.common.dto.ranking.RevokeRankingPointsRequest
import org.skopeo.domain.service.ranking.RankingPointService

/**
 * Ranking-points ledger (#146, phase 1). The per-user grant/list/revoke routes are ADMINISTRATOR-gated
 * (enforced in [RankingPointService]): grant an award to a user, list a user's awards, revoke an award.
 * The paged list-all at `GET /api/v1/ranking-points` (#472) is POINTS_MANAGER-or-ADMINISTRATOR-gated,
 * matching the Points Management tab.
 */
fun Application.configureRankingPointRoutes(service: RankingPointService = RankingPointService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/users/{userId}/ranking-points") {
                grantAndListRankingPoints(service = service)
                adjustRankingPoints(service = service)
            }
            route(path = "/api/v1/ranking-points") {
                listAllRankingPoints(service = service)
                awardDerivation(service = service)
                revokeRankingPoints(service = service)
            }
        }
    }
}

/** Paged list-all of the ledger (#472), newest-first, for the Points Management "Points awarded" list. */
private fun Route.listAllRankingPoints(service: RankingPointService) {
    get {
        respondMappingErrors {
            val params = call.request.queryParameters
            respondEither(
                result =
                    service.listAwards(
                        token = verifiedToken(),
                        limit = params["limit"]?.toIntOrNull(),
                        offset = params["offset"]?.toIntOrNull(),
                    ),
            ) { page -> call.respond(status = HttpStatusCode.OK, message = page) }
        }
    }
}

private fun Route.grantAndListRankingPoints(service: RankingPointService) {
    post {
        respondMappingErrors {
            val userId = uuidParam(name = "userId")
            val request = call.receive<GrantRankingPointsRequest>()
            respondEither(result = service.grant(token = verifiedToken(), userId = userId, request = request)) { award ->
                call.respond(status = HttpStatusCode.Created, message = award)
            }
        }
    }
    get {
        respondMappingErrors {
            respondEither(result = service.listForUser(token = verifiedToken(), userId = uuidParam(name = "userId"))) { awards ->
                call.respond(status = HttpStatusCode.OK, message = awards)
            }
        }
    }
}

/** Manual signed point adjustment (#469): an admin awards (+) or deducts (−) points for a player. */
private fun Route.adjustRankingPoints(service: RankingPointService) {
    post(path = "/adjustments") {
        respondMappingErrors {
            val userId = uuidParam(name = "userId")
            val request = call.receive<AdjustRankingPointsRequest>()
            respondEither(result = service.adjust(token = verifiedToken(), userId = userId, request = request)) { award ->
                call.respond(status = HttpStatusCode.Created, message = award)
            }
        }
    }
}

/**
 * How one award's amount was reached (#862), for the Points Management popup. Fetched per award on click
 * rather than folded into the list, so the paged table stays lean and 25 derivations are not assembled to
 * show one.
 */
private fun Route.awardDerivation(service: RankingPointService) {
    get(path = "/{awardId}/derivation") {
        respondMappingErrors {
            respondEither(
                result = service.derivation(token = verifiedToken(), awardId = uuidParam(name = "awardId")),
            ) { derivation -> call.respond(status = HttpStatusCode.OK, message = derivation) }
        }
    }
}

private fun Route.revokeRankingPoints(service: RankingPointService) {
    post(path = "/{awardId}/revoke") {
        respondMappingErrors {
            val reason = call.receive<RevokeRankingPointsRequest>().reason
            respondEither(
                result = service.revoke(token = verifiedToken(), awardId = uuidParam(name = "awardId"), reason = reason),
            ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
        }
    }
}
