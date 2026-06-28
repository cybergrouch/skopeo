// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class PublicCodeTest {
    @Test
    fun `generates a 6-char Crockford-base32 code when none collide`() {
        val code = PublicCode.generate { false }
        code.length shouldBe PublicCode.LENGTH
        code shouldMatch Regex(pattern = "[0-9A-HJKMNP-TV-Z]{6}") // no I/L/O/U
    }

    @Test
    fun `retries past a collision and returns the next free code`() {
        var calls = 0
        // The first candidate is "taken", the next is free — exercises the retry branch.
        val code = PublicCode.generate { calls++ < 1 }
        calls shouldBe 2
        code.length shouldBe PublicCode.LENGTH
    }

    @Test
    fun `throws when it cannot find a free code after the retry budget`() {
        shouldThrow<IllegalStateException> { PublicCode.generate { true } }
    }
}
