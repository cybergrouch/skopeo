// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import java.time.LocalDateTime
import java.util.UUID

/**
 * The highest-value case for [org.skopeo.common.redaction.Redactable] (#801).
 *
 * `IssuedApiKey.plaintext` is a *working* partner API key, not merely personal data: only its SHA-256
 * hash is persisted (`ApiClientService`), and authentication hashes whatever the client presents and
 * matches that. So interpolating this object into a log line would put a live credential into Cloud
 * Logging, where it would outlive the request by the retention period — and remain valid.
 */
class IssuedApiKeyRedactionTest {
    private val canary = "skopeo_live_CANARY_DO_NOT_LOG"

    private fun issued(): IssuedApiKey {
        val clientId = UUID.randomUUID()
        val now = LocalDateTime.now()
        return IssuedApiKey(
            client =
                ApiClient(
                    id = clientId,
                    name = "Probe partner",
                    status = ApiClientStatus.ACTIVE,
                    createdBy = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            key =
                ApiKey(
                    id = UUID.randomUUID(),
                    clientId = clientId,
                    keyPrefix = "skopeo_live_CANA",
                    scopes = emptySet(),
                    status = ApiKeyStatus.ACTIVE,
                    createdBy = null,
                    createdAt = now,
                    expiresAt = null,
                    lastUsedAt = null,
                    revokedAt = null,
                ),
            plaintext = canary.asRedactable(),
        )
    }

    @Test
    fun `interpolating the issued key cannot print a working credential`() {
        // Deliberately written the way the mistake would be written.
        val logLine = "issued a key: ${issued()}"

        logLine shouldNotContain canary
        logLine shouldNotContain "CANARY_DO_NOT_LOG"
    }

    @Test
    fun `the surrounding fields still render, so the line stays diagnostic`() {
        val rendered = issued().toString()

        rendered.contains(other = "Probe partner") shouldBe true
        // The key PREFIX is safe and is what identifies which key was issued — it is stored and
        // displayed by design, unlike the secret itself.
        rendered.contains(other = "skopeo_live_CANA") shouldBe true
    }

    @Test
    fun `the show-once delivery can still read the key`() {
        // Redaction must not break issuance: the caller has to receive the key exactly once.
        issued().plaintext.revealed shouldBe canary
    }
}
