// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class DuplicateServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val service = DuplicateService(users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun admin(uid: String = "root") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `an admin marks a cluster — duplicates are disabled and point at the canonical`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")
        val dup1 = provisionUser(uid = "dup1")
        val dup2 = provisionUser(uid = "dup2")

        val result =
            service.markDuplicates(
                token = token(uid = "root"),
                canonicalId = canonical.id,
                duplicateIds = listOf(dup1.id, dup2.id),
            ).shouldBeRight()

        result.map { it.id }.toSet() shouldBe setOf(dup1.id, dup2.id)
        listOf(dup1.id, dup2.id).forEach { id ->
            users.findById(id = id).shouldBeRight().let {
                it.isActive.shouldBeFalse()
                it.canonicalUserId shouldBe canonical.id
            }
        }
        // The canonical itself is untouched.
        users.findById(id = canonical.id).shouldBeRight().isActive.shouldBeTrue()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.USER_MARKED_DUPLICATE), limit = 10, offset = 0).second shouldBe 2L
    }

    @Test
    fun `restore reactivates and clears the pointer, and is audited`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")
        val dup = provisionUser(uid = "dup")
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))
            .shouldBeRight()

        service.restore(token = token(uid = "root"), id = dup.id).shouldBeRight()

        users.findById(id = dup.id).shouldBeRight().let {
            it.isActive.shouldBeTrue()
            it.canonicalUserId.shouldBeNull()
        }
        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.USER_UNMARKED_DUPLICATE), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `only an admin may mark or restore duplicates`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")
        val dup = provisionUser(uid = "dup")

        service
            .markDuplicates(token = token(uid = "dup"), canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))
            .shouldBeRight()
        service.restore(token = token(uid = "keep"), id = dup.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `marking rejects self, unknowns, empty, non-distinct input, and an unknown canonical`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")
        val dup = provisionUser(uid = "dup")

        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = emptyList())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = canonical.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // Non-distinct ids.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(dup.id, dup.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // Unknown duplicate.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        // Unknown canonical.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = UUID.randomUUID(), duplicateIds = listOf(element = dup.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `an unprovisioned caller is forbidden`() {
        val canonical = provisionUser(uid = "keep")
        val dup = provisionUser(uid = "dup")

        service
            .markDuplicates(token = token(uid = "ghost"), canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `restoring an unknown user is not-found`() {
        admin(uid = "root")

        service.restore(token = token(uid = "root"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `duplicatesOf requires an admin and an existing canonical`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")

        service
            .duplicatesOf(token = token(uid = "keep"), canonicalId = canonical.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .duplicatesOf(token = token(uid = "root"), canonicalId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a canonical cannot itself be a duplicate, and a target cannot already be a canonical`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        val c = provisionUser(uid = "c")
        // b is now a duplicate of a; a is a canonical for b.
        service.markDuplicates(token = token(uid = "root"), canonicalId = a.id, duplicateIds = listOf(element = b.id)).shouldBeRight()

        // a is already a canonical → cannot be marked a duplicate of c.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = c.id, duplicateIds = listOf(element = a.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
        // b is a duplicate → cannot be used as a canonical.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = b.id, duplicateIds = listOf(element = c.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `restoring a user that is not a duplicate is a conflict`() {
        admin(uid = "root")
        val user = provisionUser(uid = "plain")

        service.restore(token = token(uid = "root"), id = user.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `duplicatesOf lists the canonical's duplicates for an admin`() {
        admin(uid = "root")
        val canonical = provisionUser(uid = "keep")
        val dup = provisionUser(uid = "dup")
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = dup.id))
            .shouldBeRight()

        service.duplicatesOf(token = token(uid = "root"), canonicalId = canonical.id).shouldBeRight().single().id shouldBe dup.id
    }
}
