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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.settings.SetThemeRequest
import org.skopeo.dto.settings.toResponse
import org.skopeo.service.settings.ThemeService

/**
 * The global UI theme setting (#378). The GET is public (viewable anonymously, so the web UI can
 * skin its sign-in pages) while the PUT is ADMINISTRATOR-only (enforced in [ThemeService]).
 */
fun Application.configureThemeRoutes(service: ThemeService = ThemeService()) {
    routing {
        route(path = "/api/v1/theme") {
            // The theme is publicly readable so unauthenticated pages can skin themselves (#378).
            authenticate(FIREBASE_AUTH, optional = true) {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getTheme().toResponse())
                    }
                }
            }
            authenticate(FIREBASE_AUTH) {
                put {
                    respondMappingErrors {
                        val request = call.receive<SetThemeRequest>()
                        respondEither(result = service.setTheme(token = verifiedToken(), theme = request.theme)) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value.toResponse())
                        }
                    }
                }
            }
        }
    }
}
