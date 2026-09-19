// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
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
            firebaseUid = uid,
            photoUrl = null,
            dateOfBirth = dob,
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
        user().dateOfBirth shouldBe dob
        user().firebaseUid shouldBe uid
    }

    @Test
    fun `stringifying the field yields the value, not a placeholder (#825)`() {
        // The inversion of a real bug. Under the old wrapper this assertion read `shouldBe "***"`,
        // because `Redactable.toString()` redacted — and two DTO mappers did exactly
        // `dateOfBirth?.toString()`, so the API would have returned a placeholder to a client as
        // somebody's date of birth. It compiled fine, since toString() exists on everything.
        //
        // The compiler plugin cannot reproduce that. Only the ENCLOSING data class's generated
        // toString() is rewritten; the property keeps its raw type, so reading it gives the value a
        // mapper needs and a caller expects.
        user().dateOfBirth.toString() shouldBe "1979-04-11"
        user().firebaseUid.toString() shouldBe uid

        // While the object as a whole still refuses to say either out loud. (No `shouldBe` between two
        // renderings: `user()` mints a fresh random id per call, so they are never equal.)
        user().toString().contains(other = "1979-04-11") shouldBe false
        user().toString().contains(other = uid) shouldBe false
    }
}
