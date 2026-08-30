// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.redaction

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** A stand-in for a real model: the point is that IT declares no toString of its own. */
private data class Holder(
    val id: Int,
    val label: String,
    val secret: Redactable<String>,
    val birthday: Redactable<LocalDate>?,
)

class RedactableTest {
    private val canary = "SUPERSECRET-CANARY"

    @Test
    fun `the wrapper itself never renders its value`() {
        // A value class inherits a generated toString that prints the wrapped value, so the override in
        // Redactable is load-bearing rather than decorative.
        canary.asRedactable().toString() shouldBe Redactable.REDACTED
    }

    @Test
    fun `a containing data class is safe without declaring any toString of its own`() {
        // This is the whole design: redaction lives in the field's TYPE, so every model that holds one is
        // protected automatically — no per-model boilerplate, and nothing to forget on a new model.
        val holder =
            Holder(
                id = 1,
                label = "visible",
                secret = canary.asRedactable(),
                birthday = LocalDate.parse("1979-04-11").asRedactable(),
            )

        val rendered = holder.toString()

        rendered shouldNotContain canary
        rendered shouldNotContain "1979-04-11"
        // Non-sensitive fields still render, or the log line would be useless.
        rendered.contains(other = "visible") shouldBe true
    }

    @Test
    fun `the value is still readable, so models stay usable`() {
        canary.asRedactable().revealed shouldBe canary
        LocalDate.parse("1979-04-11").asRedactable().revealed shouldBe LocalDate.parse("1979-04-11")
    }

    @Test
    fun `a nullable field unwraps with the ordinary safe call`() {
        val present: Redactable<String>? = canary.asRedactable()
        val absent: Redactable<String>? = null

        present?.revealed shouldBe canary
        absent?.revealed.shouldBeNull()
    }

    @Test
    fun `copy and equality behave as they did before wrapping`() {
        val a = Holder(id = 1, label = "x", secret = canary.asRedactable(), birthday = null)

        a.copy(label = "y").secret.revealed shouldBe canary
        (a == a.copy()) shouldBe true
        (a == a.copy(secret = "other".asRedactable())) shouldBe false
    }

    @Test
    fun `the redaction marker leaks no length or type hint`() {
        // "***" rather than something like "String(19 chars)": a length is itself information.
        Redactable.REDACTED shouldBe "***"
        "a".asRedactable().toString() shouldBe "aaa".asRedactable().toString()
    }
}
