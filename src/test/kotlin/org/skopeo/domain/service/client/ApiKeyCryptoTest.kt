// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.client

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.ApiKeyEnvironment

class ApiKeyCryptoTest {
    @Test
    fun `generates a prefixed key whose hash is the SHA-256 of the plaintext`() {
        val generated = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)

        generated.plaintext.revealed shouldStartWith "skopeo_live_"
        generated.displayPrefix shouldStartWith "skopeo_live_"
        // The hash stored is the SHA-256 hex (64 chars) of the plaintext.
        generated.hash shouldHaveLength 64
        generated.hash shouldBe ApiKeyCrypto.hash(plaintext = generated.plaintext.revealed)
    }

    @Test
    fun `uses the test prefix for the TEST environment`() {
        ApiKeyCrypto.generate(environment = ApiKeyEnvironment.TEST).plaintext.revealed shouldStartWith "skopeo_test_"
    }

    @Test
    fun `generates a distinct random key each time`() {
        val a = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        val b = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        a.plaintext.revealed shouldNotBe b.plaintext.revealed
        a.hash shouldNotBe b.hash
    }

    @Test
    fun `looksValid accepts a freshly generated key`() {
        val generated = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        ApiKeyCrypto.looksValid(raw = generated.plaintext.revealed) shouldBe true
    }

    @Test
    fun `looksValid rejects an unknown prefix, a bad checksum, and empty input`() {
        ApiKeyCrypto.looksValid(raw = "") shouldBe false
        ApiKeyCrypto.looksValid(raw = "nope_ABCDEF123456") shouldBe false
        // A valid key with its last checksum char mangled fails the offline check.
        val generated = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        val tampered = generated.plaintext.revealed.dropLast(n = 1) + if (generated.plaintext.revealed.last() == '0') '1' else '0'
        ApiKeyCrypto.looksValid(raw = tampered) shouldBe false
    }

    @Test
    fun `the display prefix reveals only the leading segment, never the whole secret`() {
        val generated = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        generated.plaintext.revealed shouldContain generated.displayPrefix
        (generated.displayPrefix.length < generated.plaintext.revealed.length) shouldBe true
    }
}
