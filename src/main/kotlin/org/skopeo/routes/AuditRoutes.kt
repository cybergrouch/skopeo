// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.dto.audit.AuditCommentRequest

private const val DEFAULT_AUDIT_PAGE_SIZE = 5

/**
 * The admin trace viewer's read API (issue #102). Listing the audit log and editing an entry's
 * note are ADMINISTRATOR-only (enforced in [AuditService]).
 */
fun Application.configureAuditRoutes(service: AuditService = AuditService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/audit") {
                get {
                    respondMappingErrors {
                        val params = call.request.queryParameters
                        respondEither(
                            result =
                                service.list(
                                    token = verifiedToken(),
                                    categoryRaw = params["category"],
                                    limit = params["limit"]?.toIntOrNull() ?: DEFAULT_AUDIT_PAGE_SIZE,
                                    offset = params["offset"]?.toIntOrNull() ?: 0,
                                ),
                        ) { page -> call.respond(status = HttpStatusCode.OK, message = page) }
                    }
                }
                patch(path = "/{id}/comment") {
                    respondMappingErrors {
                        val request = call.receive<AuditCommentRequest>()
                        respondEither(
                            result = service.setComment(token = verifiedToken(), id = uuidParam(name = "id"), comment = request.comment),
                        ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
                    }
                }
            }
        }
    }
}
