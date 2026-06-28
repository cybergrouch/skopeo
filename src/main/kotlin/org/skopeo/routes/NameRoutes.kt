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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.dto.name.NameStateRequest
import org.skopeo.dto.name.toResponse
import org.skopeo.model.NameType
import org.skopeo.service.name.NameService

/**
 * User-name API, nested under the owning user. Names are append-only (add, then disable —
 * never edit or delete); every operation is self-or-ADMINISTRATOR (enforced in
 * [NameService]). Routes stay thin.
 */
fun Application.configureNameRoutes(service: NameService = NameService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/users/{userId}/names") {
                listAndCreate(service = service)
                byId(service = service)
                state(service = service)
            }
        }
    }
}

private fun Route.listAndCreate(service: NameService) {
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken(), userId = uuidParam(name = "userId"))) { list ->
                call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<NameCreateRequest>()
            respondEither(
                result =
                    service.create(
                        token = verifiedToken(),
                        userId = uuidParam(name = "userId"),
                        type = parseEnumParam<NameType>(value = request.type, field = "type"),
                        value = request.value,
                    ),
            ) { name -> call.respond(status = HttpStatusCode.Created, message = name.toResponse()) }
        }
    }
}

private fun Route.byId(service: NameService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(
                result = service.get(token = verifiedToken(), userId = uuidParam(name = "userId"), nameId = uuidParam(name = "id")),
            ) { name -> call.respond(status = HttpStatusCode.OK, message = name.toResponse()) }
        }
    }
}

private fun Route.state(service: NameService) {
    put(path = "/{id}/state") {
        respondMappingErrors {
            val request = call.receive<NameStateRequest>()
            respondEither(
                result =
                    service.setActive(
                        token = verifiedToken(),
                        userId = uuidParam(name = "userId"),
                        nameId = uuidParam(name = "id"),
                        active = request.isActive,
                    ),
            ) { name -> call.respond(status = HttpStatusCode.OK, message = name.toResponse()) }
        }
    }
}
