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
import org.skopeo.common.dto.settings.SetAwardRankingPointsRequest
import org.skopeo.common.dto.settings.SetFacebookLoginRequest
import org.skopeo.domain.service.settings.SettingsService

/**
 * App-wide feature flags backed by app_settings (#647). Each GET is publicly readable (the flag steers
 * unauthenticated sign-in/sign-up pages) while the PUT is ADMINISTRATOR-only (enforced in [SettingsService]),
 * so a flag can be flipped from the Admin tab with no redeploy.
 */
fun Application.configureFeatureFlagRoutes(service: SettingsService = SettingsService()) {
    routing {
        // Facebook login kill-switch (#647): the sign-in/sign-up pages read this to decide whether to
        // render the "Continue with Facebook" buttons, so the GET must be public.
        route(path = "/api/v1/settings/facebook-login") {
            authenticate(FIREBASE_AUTH, optional = true) {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getFacebookLoginResponse())
                    }
                }
            }
            authenticate(FIREBASE_AUTH) {
                put {
                    respondMappingErrors {
                        val request = call.receive<SetFacebookLoginRequest>()
                        respondEither(
                            result = service.setFacebookLogin(token = verifiedToken(), enabled = request.enabled),
                        ) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value)
                        }
                    }
                }
            }
        }
        // Award-ranking-points checkbox toggle (#641): the event-create form reads this to decide whether
        // to offer the checkbox; default disabled. Admin-only write.
        route(path = "/api/v1/settings/award-ranking-points") {
            authenticate(FIREBASE_AUTH, optional = true) {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getAwardRankingPointsResponse())
                    }
                }
            }
            authenticate(FIREBASE_AUTH) {
                put {
                    respondMappingErrors {
                        val request = call.receive<SetAwardRankingPointsRequest>()
                        respondEither(
                            result = service.setAwardRankingPoints(token = verifiedToken(), enabled = request.enabled),
                        ) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value)
                        }
                    }
                }
            }
        }
    }
}
