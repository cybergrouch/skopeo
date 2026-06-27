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
import org.skopeo.dto.audit.AuditCommentRequest
import org.skopeo.dto.audit.toResponse
import org.skopeo.model.AuditCategory
import org.skopeo.service.audit.AuditService

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
                        val page =
                            service.list(
                                token = verifiedToken(),
                                category = params["category"]?.let { parseCategory(raw = it) },
                                limit = params["limit"]?.toIntOrNull() ?: DEFAULT_AUDIT_PAGE_SIZE,
                                offset = params["offset"]?.toIntOrNull() ?: 0,
                            )
                        call.respond(status = HttpStatusCode.OK, message = page.toResponse())
                    }
                }
                patch(path = "/{id}/comment") {
                    respondMappingErrors {
                        val request = call.receive<AuditCommentRequest>()
                        service.setComment(token = verifiedToken(), id = uuidParam(name = "id"), comment = request.comment)
                        call.respond(status = HttpStatusCode.NoContent, message = "")
                    }
                }
            }
        }
    }
}

/** Parse a `category` filter to an [AuditCategory]; an unknown value is a 400. */
private fun parseCategory(raw: String): AuditCategory =
    requireNotNull(value = AuditCategory.entries.find { it.name == raw.uppercase() }) {
        "Unknown audit category '$raw'; expected one of ${AuditCategory.entries.joinToString { it.name }}"
    }
