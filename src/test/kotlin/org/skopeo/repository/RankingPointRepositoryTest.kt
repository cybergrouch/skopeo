// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.NameType
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class RankingPointRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val awards = RankingPointRepository()

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
        ).id

    private fun write(
        userId: UUID,
        points: String = "100",
        validFrom: LocalDateTime = LocalDateTime.now(),
        validUntil: LocalDateTime = LocalDateTime.now().plusMonths(12),
        grantedBy: UUID? = null,
    ) = RankingPointAwardWrite(
        userId = userId,
        points = BigDecimal(points),
        pointClass = PointClass.ANNUAL_TOURNAMENT,
        sourceType = PointSourceType.INTERNAL,
        sourceId = "src-1",
        band = "4.0",
        sex = "Male",
        reason = "for winning",
        validFrom = validFrom,
        validUntil = validUntil,
        status = AwardStatus.ACTIVE,
        revokesAwardId = null,
        grantedBy = grantedBy,
        awardedAt = LocalDateTime.now(),
    )

    @Test
    fun `award inserts an active row that round-trips via findById`() {
        val user = newUser(uid = "p1")
        val admin = newUser(uid = "admin")

        val award = awards.award(write = write(userId = user, grantedBy = admin))
        award.status shouldBe AwardStatus.ACTIVE
        award.points shouldBe BigDecimal("100.0000")
        award.band shouldBe "4.0"
        award.grantedBy shouldBe admin

        awards.findById(id = award.id)!!.id shouldBe award.id
        awards.findById(id = UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `listByUser returns all rows newest-first for the user only`() {
        val user = newUser(uid = "p1")
        val other = newUser(uid = "p2")

        awards.award(write = write(userId = user, points = "10"))
        awards.award(write = write(userId = user, points = "20"))
        awards.award(write = write(userId = other, points = "99"))

        awards.listByUser(userId = user) shouldHaveSize 2
        awards.listByUser(userId = other).single().points shouldBe BigDecimal("99.0000")
    }

    @Test
    fun `revoke flips the original to REVOKED and appends a marker row referencing it`() {
        val user = newUser(uid = "p1")
        val admin = newUser(uid = "admin")
        val original = awards.award(write = write(userId = user))

        val marker = awards.revoke(awardId = original.id, revokedBy = admin, reason = "mistake", revokedAt = LocalDateTime.now())!!
        marker.status shouldBe AwardStatus.REVOKED
        marker.points shouldBe BigDecimal("0.0000")
        marker.revokesAwardId shouldBe original.id
        marker.reason shouldBe "mistake"

        // The original is now REVOKED.
        awards.findById(id = original.id)!!.status shouldBe AwardStatus.REVOKED
        // The ledger holds both rows.
        awards.listByUser(userId = user) shouldHaveSize 2

        // A second revoke of the same (now revoked) award is a no-op → null.
        awards.revoke(awardId = original.id, revokedBy = admin, reason = null, revokedAt = LocalDateTime.now()).shouldBeNull()
        // Revoking a missing award is a no-op → null.
        awards.revoke(awardId = UUID.randomUUID(), revokedBy = admin, reason = null, revokedAt = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `activeAsOf returns only active in-window awards`() {
        val user = newUser(uid = "p1")
        val now = LocalDateTime.of(2026, 6, 1, 0, 0)

        // In-window, active.
        val inWindow = awards.award(write = write(userId = user, validFrom = now.minusMonths(1), validUntil = now.plusMonths(1)))
        // Expired (window ended before now).
        awards.award(write = write(userId = user, validFrom = now.minusMonths(6), validUntil = now.minusMonths(3)))
        // Future (not started yet).
        awards.award(write = write(userId = user, validFrom = now.plusMonths(1), validUntil = now.plusMonths(6)))
        // Active + in-window but then revoked → drops out.
        val revoked = awards.award(write = write(userId = user, validFrom = now.minusMonths(1), validUntil = now.plusMonths(1)))
        awards.revoke(awardId = revoked.id, revokedBy = null, reason = null, revokedAt = now)

        awards.activeAsOf(asOf = now).map { it.id } shouldContainExactly listOf(element = inWindow.id)
    }
}
