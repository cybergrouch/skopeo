// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.DuplicateCandidateRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class DuplicateCandidateServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val candidates = DuplicateCandidateRepository()
    private val service = DuplicateCandidateService(candidates = candidates, users = users)

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
    fun `flagManual creates a candidate (idempotent per pair) and list resolves both accounts`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")

        service.flagManual(token = token(uid = "root"), userAId = a.id, userBId = b.id, reason = "same person").shouldBeRight()
        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.DUPLICATE_CANDIDATE_FLAGGED), limit = 10, offset = 0).second shouldBe 1L

        // Re-flagging the same (unordered) pair does not stack a second OPEN candidate.
        service.flagManual(token = token(uid = "root"), userAId = b.id, userBId = a.id, reason = "again").shouldBeRight()

        val page =
            service.list(token = token(uid = "root"), limit = 50, offset = 0, status = DuplicateCandidateStatus.OPEN).shouldBeRight()
        page.items shouldHaveSize 1
        setOf(page.items.single().userA.id, page.items.single().userB.id) shouldBe setOf(a.id, b.id)
    }

    @Test
    fun `flagManual validates the pair`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")

        service
            .flagManual(token = token(uid = "root"), userAId = a.id, userBId = a.id, reason = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // Either side missing is rejected.
        service
            .flagManual(token = token(uid = "root"), userAId = UUID.randomUUID(), userBId = a.id, reason = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .flagManual(token = token(uid = "root"), userAId = a.id, userBId = UUID.randomUUID(), reason = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `confirm keeping the second-ordered account marks the first a duplicate`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        val candidate =
            service.flagManual(token = token(uid = "root"), userAId = a.id, userBId = b.id, reason = null).shouldBeRight().candidate

        // Keep the userB side → the userA side is the one disabled (the inverse confirm branch).
        service.confirm(token = token(uid = "root"), id = candidate.id, canonicalId = candidate.userBId).shouldBeRight()

        users.findById(id = candidate.userAId).shouldBeRight().let {
            it.isActive.shouldBeFalse()
            it.canonicalUserId shouldBe candidate.userBId
        }
        candidates.findById(id = candidate.id).shouldBeRight().status shouldBe DuplicateCandidateStatus.RESOLVED
    }

    @Test
    fun `dismiss closes an open candidate and is audited`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        val candidate =
            service.flagManual(token = token(uid = "root"), userAId = a.id, userBId = b.id, reason = null).shouldBeRight().candidate

        service.dismiss(token = token(uid = "root"), id = candidate.id).shouldBeRight()

        candidates.findById(id = candidate.id).shouldBeRight().status shouldBe DuplicateCandidateStatus.DISMISSED
        // A second action on a non-open candidate conflicts.
        service.dismiss(token = token(uid = "root"), id = candidate.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.DUPLICATE_CANDIDATE_DISMISSED), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `confirm marks the other account a duplicate, resolves the candidate, and audits`() {
        admin(uid = "root")
        val keep = provisionUser(uid = "keep")
        val dupe = provisionUser(uid = "dupe")
        val candidate =
            service.flagManual(token = token(uid = "root"), userAId = keep.id, userBId = dupe.id, reason = null).shouldBeRight().candidate

        service.confirm(token = token(uid = "root"), id = candidate.id, canonicalId = keep.id).shouldBeRight()

        users.findById(id = dupe.id).shouldBeRight().let {
            it.isActive.shouldBeFalse()
            it.canonicalUserId shouldBe keep.id
        }
        candidates.findById(id = candidate.id).shouldBeRight().status shouldBe DuplicateCandidateStatus.RESOLVED
        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.DUPLICATE_CANDIDATE_CONFIRMED), limit = 10, offset = 0).second shouldBe 1L
        audit.list(actions = listOf(element = AuditAction.USER_MARKED_DUPLICATE), limit = 10, offset = 0).second shouldBe 1L
    }

    @Test
    fun `confirm rejects a canonical that is not one of the candidate's accounts`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        val outsider = provisionUser(uid = "c")
        val candidate =
            service.flagManual(token = token(uid = "root"), userAId = a.id, userBId = b.id, reason = null).shouldBeRight().candidate

        service
            .confirm(token = token(uid = "root"), id = candidate.id, canonicalId = outsider.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `dismiss and confirm reject an unknown candidate`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")

        service.dismiss(token = token(uid = "root"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .confirm(token = token(uid = "root"), id = UUID.randomUUID(), canonicalId = a.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `every action is ADMINISTRATOR-only`() {
        admin(uid = "root")
        val a = provisionUser(uid = "a")
        val b = provisionUser(uid = "b")
        val candidate =
            service.flagManual(token = token(uid = "root"), userAId = a.id, userBId = b.id, reason = null).shouldBeRight().candidate

        service
            .list(token = token(uid = "a"), limit = 50, offset = 0, status = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unknown caller (no such user) is also refused.
        service
            .list(token = token(uid = "ghost"), limit = 50, offset = 0, status = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .flagManual(token = token(uid = "a"), userAId = a.id, userBId = b.id, reason = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service.dismiss(token = token(uid = "a"), id = candidate.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .confirm(token = token(uid = "a"), id = candidate.id, canonicalId = a.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
