// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import java.time.LocalDate
import java.util.UUID

/**
 * `User` is the type threaded through nearly every service, so it is the one most likely to end up in a
 * log line while debugging (#801 criterion 5, deferred at the time on cost/benefit and now met).
 *
 * The estimate that deferred it was wrong, which is worth recording: raw `grep` counted ~126 occurrences
 * of `dateOfBirth` and ~238 of `firebaseUid`, but most were DTO fields, entity columns and Exposed column
 * definitions that are not being wrapped. The compiler-measured churn was **18 main sites**.
 */
class UserRedactionTest {
    private val dob = LocalDate.parse("1979-04-11")
    private val uid = "firebase-uid-CANARY"

    private fun user() =
        User(
            id = UUID.randomUUID(),
            publicCode = "K7Q2MX",
            firebaseUid = uid.asRedactable(),
            photoUrl = null,
            dateOfBirth = dob.asRedactable(),
            sex = "Male",
            city = "Cebu",
            country = "PH",
            kycVerified = false,
            isActive = true,
            names = emptyList(),
            contacts = emptyList(),
            identities = emptyList(),
            capabilities = emptySet(),
        )

    @Test
    fun `interpolating a user leaks neither the date of birth nor the firebase uid`() {
        // Written the way the mistake would actually be written.
        val logLine = "provisioned ${user()}"

        logLine shouldNotContain uid
        logLine shouldNotContain "1979-04-11"
        logLine shouldNotContain "1979"
    }

    @Test
    fun `the fields that identify the record without exposing the person still render`() {
        val rendered = user().toString()

        // A log line has to stay useful: the public code is the shareable identifier, and city/country
        // are already public on player pages.
        rendered.contains(other = "K7Q2MX") shouldBe true
        rendered.contains(other = "Cebu") shouldBe true
    }

    @Test
    fun `both values remain readable, so age computation and auth still work`() {
        user().dateOfBirth?.revealed shouldBe dob
        user().firebaseUid?.revealed shouldBe uid
    }

    @Test
    fun `stringifying the wrapper instead of revealing it would ship a placeholder to the client`() {
        // The bug this pins down was real and reached production code: two DTO mappers did
        // `dateOfBirth?.toString()`, which on a Redactable yields "***" — so the API would have
        // returned a redacted placeholder instead of the date, and it compiled fine because
        // toString() exists on everything.
        //
        // This is the same blind spot as string interpolation, and it is why wrapping a field needs the
        // full suite rather than a clean compile: neither the type checker nor detekt can see it.
        val wrapped = dob.asRedactable()

        wrapped.toString() shouldBe "***"
        wrapped.revealed.toString() shouldBe "1979-04-11"
    }
}
