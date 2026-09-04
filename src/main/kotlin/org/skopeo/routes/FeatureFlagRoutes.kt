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
import org.skopeo.common.dto.settings.SetAwardRankingPointsRequest
import org.skopeo.common.dto.settings.SetCalibrationMatchesRequest
import org.skopeo.common.dto.settings.SetFacebookLoginRequest
import org.skopeo.common.dto.settings.SetHideRankingPointsRequest
import org.skopeo.domain.service.settings.SettingsService

/**
 * App-wide feature flags backed by app_settings (#647). Each GET is publicly readable (the flag steers
 * unauthenticated sign-in/sign-up pages) while the PUT is ADMINISTRATOR-only (enforced in [SettingsService]),
 * so a flag can be flipped from the Admin tab with no redeploy.
 */
fun Application.configureFeatureFlagRoutes(service: SettingsService = SettingsService()) {
    routing {
        facebookLoginFlag(service = service)
        awardRankingPointsFlag(service = service)
        hideRankingPointsFlag(service = service)
        calibrationMatchesSetting(service = service)
    }
}

/**
 * Facebook login kill-switch (#647). The sign-in/sign-up pages read this to decide whether to render the
 * "Continue with Facebook" buttons, so the GET must be public.
 */
private fun Route.facebookLoginFlag(service: SettingsService) {
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
}

/**
 * Award-ranking-points toggle (#641). The event-create form reads this to decide whether to offer the
 * checkbox; default disabled. Admin-only write.
 *
 * Not to be confused with [hideRankingPointsFlag]: this one governs whether points are **awarded**.
 */
private fun Route.awardRankingPointsFlag(service: SettingsService) {
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

/**
 * Hide-ranking-points toggle (#865). The read is public — a client needs it to know whether to render a
 * points column at all — while the write is ADMINISTRATOR-only, enforced in the service.
 *
 * Not to be confused with [awardRankingPointsFlag]: this one governs whether points are **displayed**, and
 * its default is the opposite way round (unticked shows).
 */
private fun Route.hideRankingPointsFlag(service: SettingsService) {
    route(path = "/api/v1/settings/hide-ranking-points") {
        authenticate(FIREBASE_AUTH, optional = true) {
            get {
                respondMappingErrors {
                    call.respond(status = HttpStatusCode.OK, message = service.getHideRankingPointsResponse())
                }
            }
        }
        authenticate(FIREBASE_AUTH) {
            put {
                respondMappingErrors {
                    val request = call.receive<SetHideRankingPointsRequest>()
                    respondEither(
                        result = service.setHideRankingPoints(token = verifiedToken(), hidden = request.hidden),
                    ) { value ->
                        call.respond(status = HttpStatusCode.OK, message = value)
                    }
                }
            }
        }
    }
}

/**
 * The calibration window's N (#881). Read is public, write is ADMINISTRATOR-only (enforced, and validated,
 * in the service).
 *
 * Public read because the number is policy rather than a secret, and the band indicator needs it to say
 * "match 3 of 10" — a client that had to guess N would eventually disagree with the server about who is
 * still calibrating, which is the whole class of bug this feature has to avoid.
 */
private fun Route.calibrationMatchesSetting(service: SettingsService) {
    route(path = "/api/v1/settings/calibration-matches") {
        authenticate(FIREBASE_AUTH, optional = true) {
            get {
                respondMappingErrors {
                    call.respond(status = HttpStatusCode.OK, message = service.getCalibrationMatchesResponse())
                }
            }
        }
        authenticate(FIREBASE_AUTH) {
            put {
                respondMappingErrors {
                    val request = call.receive<SetCalibrationMatchesRequest>()
                    respondEither(
                        result = service.setCalibrationMatches(token = verifiedToken(), matches = request.matches),
                    ) { value ->
                        call.respond(status = HttpStatusCode.OK, message = value)
                    }
                }
            }
        }
    }
}
