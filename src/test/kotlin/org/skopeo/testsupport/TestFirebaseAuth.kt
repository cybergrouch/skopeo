// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.skopeo.FirebaseAuthSettings
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * A self-contained Firebase token issuer for tests: generates an RSA key pair, serves
 * the public half through a [JwkProvider], and mints RS256 tokens the production
 * verifier accepts — exercising the real auth path with no network or emulator.
 */
object TestFirebaseAuth {
    const val ISSUER = "https://securetoken.google.com/skopeo-test"
    const val AUDIENCE = "skopeo-test"
    private const val KEY_ID = "test-key"
    private const val RSA_KEY_SIZE = 2048

    private val keyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    val settings: FirebaseAuthSettings =
        FirebaseAuthSettings(
            issuer = ISSUER,
            audience = AUDIENCE,
            jwkProvider = JwkProvider { jwk() },
        )

    private fun jwk(): Jwk {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val attributes =
            mapOf<String, Any>(
                "n" to encoder.encodeToString(publicKey.modulus.toByteArray()),
                "e" to encoder.encodeToString(publicKey.publicExponent.toByteArray()),
            )
        return Jwk(KEY_ID, "RSA", "RS256", "sig", emptyList<String>(), null, null, null, attributes)
    }

    fun mintToken(
        uid: String,
        email: String? = null,
        emailVerified: Boolean = false,
        name: String? = null,
        signInProvider: String = "password",
    ): String {
        val builder =
            JWT
                .create()
                .withKeyId(KEY_ID)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(uid)
                .withClaim(
                    "firebase",
                    mapOf(
                        "sign_in_provider" to signInProvider,
                        "identities" to mapOf(signInProvider to listOf(uid)),
                    ),
                ).withClaim("email_verified", emailVerified)
        email?.let { builder.withClaim("email", it) }
        name?.let { builder.withClaim("name", it) }
        return builder.sign(Algorithm.RSA256(publicKey, privateKey))
    }
}
