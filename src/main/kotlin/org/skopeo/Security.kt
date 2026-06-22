// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.net.URI
import java.util.concurrent.TimeUnit

/** Authentication provider name for Firebase-issued JWTs. Use with `authenticate(FIREBASE_AUTH) { ... }`. */
const val FIREBASE_AUTH = "firebase"

private const val JWK_CACHE_SIZE = 10L
private const val JWK_CACHE_EXPIRY_HOURS = 24L
private const val JWK_RATE_LIMIT_BUCKET = 10L
private const val JWK_RATE_LIMIT_MINUTES = 1L

// Google's public keys for Firebase-issued ID tokens, in JWK Set format.
private const val FIREBASE_JWK_URL =
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"

/**
 * What it takes to verify a Firebase ID token: the expected issuer/audience and the
 * source of public keys. Production reads this from config (Google's JWKS); tests can
 * inject a local key set to mint and verify tokens without any network dependency.
 */
class FirebaseAuthSettings(
    val issuer: String,
    val audience: String,
    val jwkProvider: JwkProvider,
)

/**
 * Verify Firebase ID tokens (RS256) using Google's public keys — pure public-key
 * verification, no service-account key required.
 *
 * The Firebase project id (`firebase.projectId`, overridable via `FIREBASE_PROJECT_ID`)
 * is the trust anchor: issuer = `https://securetoken.google.com/<projectId>` and
 * audience = `<projectId>`. Protect routes with `authenticate(FIREBASE_AUTH) { ... }`;
 * the verified identity is available as a `JWTPrincipal`.
 *
 * Pass [settings] to override the trust anchor (used by tests); when null it is read
 * from configuration and pointed at Google's JWKS.
 */
fun Application.configureSecurity(settings: FirebaseAuthSettings? = null) {
    val resolved = settings ?: firebaseAuthSettingsFromConfig()

    install(Authentication) {
        jwt(FIREBASE_AUTH) {
            realm = "skopeo"
            verifier(resolved.jwkProvider, resolved.issuer) {
                withAudience(resolved.audience)
                withIssuer(resolved.issuer)
            }
            validate { credential ->
                if (credential.payload.subject != null && credential.payload.audience.contains(resolved.audience)) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    status = HttpStatusCode.Unauthorized,
                    message = mapOf("error" to "Unauthorized", "message" to "A valid Firebase token is required"),
                )
            }
        }
    }
}

private fun Application.firebaseAuthSettingsFromConfig(): FirebaseAuthSettings {
    // propertyOrNull so tests (which run without application.yaml loaded) fall back to a
    // placeholder; real environments supply firebase.projectId via FIREBASE_PROJECT_ID.
    val projectId = environment.config.propertyOrNull("firebase.projectId")?.getString() ?: "skopeo-dev"
    val issuer = "https://securetoken.google.com/$projectId"
    val jwkProvider =
        JwkProviderBuilder(URI(FIREBASE_JWK_URL).toURL())
            .cached(JWK_CACHE_SIZE, JWK_CACHE_EXPIRY_HOURS, TimeUnit.HOURS)
            .rateLimited(JWK_RATE_LIMIT_BUCKET, JWK_RATE_LIMIT_MINUTES, TimeUnit.MINUTES)
            .build()
    return FirebaseAuthSettings(issuer = issuer, audience = projectId, jwkProvider = jwkProvider)
}
