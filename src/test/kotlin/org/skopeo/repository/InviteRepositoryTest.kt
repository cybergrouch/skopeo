// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.InviteStatus
import org.skopeo.model.ServiceError
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class InviteRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val invites = InviteRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private val future = LocalDateTime.now().plusDays(7)
    private val past = LocalDateTime.now().minusDays(1)

    @Test
    fun `createOrRotate inserts a pending invite, then rotates the same row's expiry on re-invite`() {
        val first = invites.createOrRotate(email = "a@x.dev", invitedBy = null, expiresAt = past)
        first.status shouldBe InviteStatus.PENDING

        val rotated = invites.createOrRotate(email = "a@x.dev", invitedBy = null, expiresAt = future)
        rotated.id shouldBe first.id // same row rotated, not a duplicate
        // Expiry moved forward (past → future); compare semantically to avoid DB datetime-precision flakiness.
        rotated.expiresAt.isAfter(first.expiresAt) shouldBe true

        val (items, total) = invites.list(limit = 50, offset = 0)
        total shouldBe 1L
        items shouldHaveSize 1
    }

    @Test
    fun `findOpenByEmail returns only a pending, unexpired invite`() {
        invites.createOrRotate(email = "open@x.dev", invitedBy = null, expiresAt = future)
        invites.createOrRotate(email = "expired@x.dev", invitedBy = null, expiresAt = past)

        invites.findOpenByEmail(email = "open@x.dev", asOf = LocalDateTime.now()).shouldNotBeNull()
        invites.findOpenByEmail(email = "expired@x.dev", asOf = LocalDateTime.now()).shouldBeNull()
        invites.findOpenByEmail(email = "nobody@x.dev", asOf = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `markAccepted closes the invite so it is no longer open`() {
        invites.createOrRotate(email = "acc@x.dev", invitedBy = null, expiresAt = future)

        invites.markAccepted(email = "acc@x.dev", acceptedAt = LocalDateTime.now())

        invites.findOpenByEmail(email = "acc@x.dev", asOf = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `markAccepted is a no-op when no pending invite exists for the email`() {
        invites.markAccepted(email = "nobody@x.dev", acceptedAt = LocalDateTime.now())

        invites.findOpenByEmail(email = "nobody@x.dev", asOf = LocalDateTime.now()).shouldBeNull()
        invites.list(limit = 50, offset = 0).second shouldBe 0L
    }

    @Test
    fun `revoke closes a pending invite and reports NotFound for an unknown id`() {
        val invite = invites.createOrRotate(email = "rev@x.dev", invitedBy = null, expiresAt = future)

        val revoked = invites.revoke(id = invite.id).shouldBeRight()
        revoked.status shouldBe InviteStatus.REVOKED
        invites.findOpenByEmail(email = "rev@x.dev", asOf = LocalDateTime.now()).shouldBeNull()

        invites.revoke(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `list paginates by limit and offset with a total`() {
        repeat(times = 3) { invites.createOrRotate(email = "p$it@x.dev", invitedBy = null, expiresAt = future) }

        val (firstPage, total) = invites.list(limit = 2, offset = 0)
        val (secondPage, _) = invites.list(limit = 2, offset = 2)

        total shouldBe 3L
        firstPage shouldHaveSize 2
        secondPage shouldHaveSize 1
    }

    @Test
    fun `list filters by stored status, scoping the total to the filter`() {
        invites.createOrRotate(email = "pending@x.dev", invitedBy = null, expiresAt = future)
        invites.createOrRotate(email = "expired@x.dev", invitedBy = null, expiresAt = past) // still stored PENDING
        invites.createOrRotate(email = "acc@x.dev", invitedBy = null, expiresAt = future)
        invites.markAccepted(email = "acc@x.dev", acceptedAt = LocalDateTime.now())
        val revoked = invites.createOrRotate(email = "rev@x.dev", invitedBy = null, expiresAt = future)
        invites.revoke(id = revoked.id)

        // PENDING covers both pending and expired (expired is a derived view of PENDING).
        val (pending, pendingTotal) = invites.list(limit = 50, offset = 0, status = InviteStatus.PENDING)
        pendingTotal shouldBe 2L
        pending.map { it.email }.toSet() shouldBe setOf("pending@x.dev", "expired@x.dev")

        val (accepted, acceptedTotal) = invites.list(limit = 50, offset = 0, status = InviteStatus.ACCEPTED)
        acceptedTotal shouldBe 1L
        accepted.single().email shouldBe "acc@x.dev"

        val (all, allTotal) = invites.list(limit = 50, offset = 0)
        allTotal shouldBe 4L
        all shouldHaveSize 4
    }
}
