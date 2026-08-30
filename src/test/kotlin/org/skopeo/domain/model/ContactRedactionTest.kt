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
 * Contacts hold the email addresses and phone numbers (#801). Both forms are covered: [ContactInfo] is
 * the input shape and [Contact] the stored one, and wrapping only one would leave the other leaking —
 * services deal in [Contact] far more often.
 */
class ContactRedactionTest {
    private val address = "juan.canary@example.invalid"

    @Test
    fun `a stored contact does not print its address`() {
        val contact =
            Contact(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                type = ContactType.EMAIL,
                value = address.asRedactable(),
                source = ContactSource.MANUAL,
                status = VerificationStatus.VERIFIED,
            )

        val rendered = "contact: $contact"

        rendered shouldNotContain address
        // Type and status stay visible — they are what make the line diagnostic.
        rendered.contains(other = "EMAIL") shouldBe true
        rendered.contains(other = "VERIFIED") shouldBe true
    }

    @Test
    fun `an incoming contact does not print its address either`() {
        val info =
            ContactInfo(
                type = ContactType.PHONE,
                value = "+639170000000".asRedactable(),
                source = ContactSource.MANUAL,
                status = VerificationStatus.PENDING,
            )

        "$info" shouldNotContain "+639170000000"
    }

    @Test
    fun `an invite does not print the invitee address`() {
        val invite =
            Invite(
                id = UUID.randomUUID(),
                email = address.asRedactable(),
                status = InviteStatus.PENDING,
                invitedBy = null,
                expiresAt = LocalDateTime.now().plusDays(7),
                createdAt = LocalDateTime.now(),
            )

        "$invite" shouldNotContain address
    }
}
