// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable

/**
 * [VerifiedFirebaseToken] is built on **every authenticated request** from the JWT claims, so it is the
 * object most likely to be interpolated into a log line while debugging auth (#801).
 */
class VerifiedFirebaseTokenRedactionTest {
    private val email = "juan.canary@example.invalid"
    private val providerUid = "google-subject-CANARY"

    private fun token() =
        VerifiedFirebaseToken(
            uid = "firebase-uid-1",
            email = email.asRedactable(),
            emailVerified = true,
            name = "Juan Canary",
            picture = "https://example.invalid/p.jpg",
            signInProvider = "google.com",
            providerUid = providerUid.asRedactable(),
        )

    @Test
    fun `interpolating a verified token leaks neither the email nor the provider subject`() {
        val logLine = "verified: ${token()}"

        logLine shouldNotContain email
        logLine shouldNotContain providerUid
    }

    @Test
    fun `the fields that are safe to see still render`() {
        val rendered = token().toString()

        // The firebase uid and display name stay visible: uid is an opaque internal id we log on purpose,
        // and the display name is shown publicly on player pages anyway.
        rendered.contains(other = "firebase-uid-1") shouldBe true
        rendered.contains(other = "google.com") shouldBe true
    }

    @Test
    fun `the values remain readable, so auth still works`() {
        token().email?.revealed shouldBe email
        token().providerUid.revealed shouldBe providerUid
    }
}
