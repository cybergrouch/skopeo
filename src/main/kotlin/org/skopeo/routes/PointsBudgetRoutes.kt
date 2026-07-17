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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.points.SetClubBudgetRequest
import org.skopeo.dto.points.SetPointsPolicyRequest
import org.skopeo.dto.points.toResponse
import org.skopeo.model.EventType
import org.skopeo.model.PointsPolicy
import org.skopeo.service.points.PointsBudgetService

/**
 * Points budget management (#403 Phase B): the global master policy and per-club per-type budgets.
 * All routes are points-manager-gated in [PointsBudgetService] (ADMINISTRATOR is implicitly a points
 * manager). The event type is parsed to the enum at the boundary (a 400 for an unknown value).
 */
fun Application.configurePointsBudgetRoutes(service: PointsBudgetService = PointsBudgetService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/points") {
                pointsPolicyRoutes(service = service)
                pointsBudgetReadRoutes(service = service)
            }
            clubBudgetWriteRoute(service = service)
        }
    }
}

/** Read all policies and set one type's global policy (points-manager). */
private fun Route.pointsPolicyRoutes(service: PointsBudgetService) {
    get(path = "/policies") {
        respondMappingErrors {
            respondEither(result = service.policies(token = verifiedToken())) { policies ->
                call.respond(status = HttpStatusCode.OK, message = policies.map { it.toResponse() })
            }
        }
    }
    put(path = "/policies/{eventType}") {
        respondMappingErrors {
            val eventType = parseEnumParam<EventType>(value = call.parameters["eventType"].orEmpty(), field = "eventType")
            val body = call.receive<SetPointsPolicyRequest>()
            val policy =
                PointsPolicy(
                    eventType = eventType,
                    minPoints = body.minPoints,
                    maxPoints = body.maxPoints,
                    maxValidityDays = body.maxValidityDays,
                )
            respondEither(result = service.setPolicy(token = verifiedToken(), policy = policy)) { saved ->
                call.respond(status = HttpStatusCode.OK, message = saved.toResponse())
            }
        }
    }
}

/** Read all clubs' per-type budgets (points-manager). */
private fun Route.pointsBudgetReadRoutes(service: PointsBudgetService) {
    get(path = "/budgets") {
        respondMappingErrors {
            respondEither(result = service.clubBudgets(token = verifiedToken(), clubId = null)) { views ->
                call.respond(status = HttpStatusCode.OK, message = views.map { it.toResponse() })
            }
        }
    }
}

/** Set a club's budget for one event type (points-manager). */
private fun Route.clubBudgetWriteRoute(service: PointsBudgetService) {
    put(path = "/api/v1/clubs/{clubId}/point-budgets/{eventType}") {
        respondMappingErrors {
            val clubId = uuidParam(name = "clubId")
            val eventType = parseEnumParam<EventType>(value = call.parameters["eventType"].orEmpty(), field = "eventType")
            val body = call.receive<SetClubBudgetRequest>()
            respondEither(
                result =
                    service.setClubBudget(
                        token = verifiedToken(),
                        clubId = clubId,
                        eventType = eventType,
                        points = body.budgetedPoints,
                    ),
            ) { view -> call.respond(status = HttpStatusCode.OK, message = view.toResponse()) }
        }
    }
}
