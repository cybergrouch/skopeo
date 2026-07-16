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
import org.skopeo.dto.ranking.GrantRankingPointsRequest
import org.skopeo.dto.ranking.RevokeRankingPointsRequest
import org.skopeo.dto.ranking.toResponse
import org.skopeo.service.ranking.RankingPointService

/**
 * Ranking-points ledger (#146, phase 1). All routes are ADMINISTRATOR-gated (enforced in
 * [RankingPointService]): grant an award to a user, list a user's awards, and revoke an award.
 */
fun Application.configureRankingPointRoutes(service: RankingPointService = RankingPointService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/users/{userId}/ranking-points") {
                grantAndListRankingPoints(service = service)
            }
            route(path = "/api/v1/ranking-points") {
                revokeRankingPoints(service = service)
            }
        }
    }
}

private fun Route.grantAndListRankingPoints(service: RankingPointService) {
    post {
        respondMappingErrors {
            val userId = uuidParam(name = "userId")
            val command = call.receive<GrantRankingPointsRequest>().toCommand(userId = userId)
            respondEither(result = service.grant(token = verifiedToken(), command = command)) { award ->
                call.respond(status = HttpStatusCode.Created, message = award.toResponse())
            }
        }
    }
    get {
        respondMappingErrors {
            respondEither(result = service.listForUser(token = verifiedToken(), userId = uuidParam(name = "userId"))) { awards ->
                call.respond(status = HttpStatusCode.OK, message = awards.map { it.toResponse() })
            }
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
