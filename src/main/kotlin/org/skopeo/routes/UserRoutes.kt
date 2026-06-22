package org.skopeo.routes

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.UserIdentityClaims

/**
 * User-management routes.
 *
 * Currently a single authenticated probe that echoes the verified Firebase identity,
 * proving the auth path end to end. CRUD over the persisted user store is added next.
 */
fun Application.configureUserRoutes() {
    routing {
        authenticate(FIREBASE_AUTH) {
            get("/api/v1/users/me") {
                val payload = call.principal<JWTPrincipal>()!!.payload
                val signInProvider = payload.getClaim("firebase").asMap()?.get("sign_in_provider") as? String
                call.respond(
                    UserIdentityClaims(
                        uid = payload.subject,
                        email = payload.getClaim("email").asString(),
                        emailVerified = payload.getClaim("email_verified").asBoolean(),
                        name = payload.getClaim("name").asString(),
                        picture = payload.getClaim("picture").asString(),
                        signInProvider = signInProvider,
                    ),
                )
            }
        }
    }
}
