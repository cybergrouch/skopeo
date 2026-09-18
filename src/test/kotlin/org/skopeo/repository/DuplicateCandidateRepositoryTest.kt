// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.DuplicateCandidateStatus
import org.skopeo.domain.model.DuplicateSignal
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class DuplicateCandidateRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val candidates = DuplicateCandidateRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).toDomain().id

    @Test
    fun `flag stores an ordered pair and is idempotent regardless of argument order`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")

        val first = candidates.flag(userAId = a, userBId = b, signal = DuplicateSignal.MANUAL, detail = "x", flaggedBy = null)
        // Re-flag with the arguments swapped → same OPEN candidate (no second row).
        val second = candidates.flag(userAId = b, userBId = a, signal = DuplicateSignal.DUPLICATE_PHONE, detail = "y", flaggedBy = null)

        second.id shouldBe first.id
        // Stored ordered: user_a_id < user_b_id.
        (first.userAId < first.userBId) shouldBe true
        candidates.list(limit = 50, offset = 0, status = null).first shouldHaveSize 1
    }

    @Test
    fun `list with no status filter returns all, and setStatus on an unknown id is not found`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        candidates.flag(userAId = a, userBId = b, signal = DuplicateSignal.MANUAL, detail = null, flaggedBy = null)

        candidates.list(limit = 50, offset = 0, status = null).second shouldBe 1L
        candidates
            .setStatus(
                id = UUID.randomUUID(),
                status = DuplicateCandidateStatus.DISMISSED,
                resolvedBy = null,
                resolvedAt = LocalDateTime.now(),
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setStatus transitions an existing candidate and findById reads it back`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val candidate = candidates.flag(userAId = a, userBId = b, signal = DuplicateSignal.MANUAL, detail = null, flaggedBy = null)

        val resolved =
            candidates
                .setStatus(
                    id = candidate.id,
                    status = DuplicateCandidateStatus.RESOLVED,
                    resolvedBy = a,
                    resolvedAt = LocalDateTime.now(),
                ).shouldBeRight()
        resolved.status shouldBe DuplicateCandidateStatus.RESOLVED.name
        candidates.findById(id = candidate.id).shouldBeRight().status shouldBe DuplicateCandidateStatus.RESOLVED.name
        candidates.findById(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
