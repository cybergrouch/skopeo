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
        awardedAt: LocalDateTime = LocalDateTime.now(),
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
        awardedAt = awardedAt,
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
    fun `listAwards returns a newest-first page of all users' rows plus the full total (#472)`() {
        val user = newUser(uid = "p1")
        val other = newUser(uid = "p2")
        val base = LocalDateTime.of(2026, 6, 1, 0, 0)

        // Three rows across two users, awarded at increasing times → newest-first is c, b, a.
        val a = awards.award(write = write(userId = user, points = "10", awardedAt = base))
        val b = awards.award(write = write(userId = other, points = "20", awardedAt = base.plusHours(1)))
        val c = awards.award(write = write(userId = user, points = "30", awardedAt = base.plusHours(2)))

        val (rows, total) = awards.listAwards(limit = 25, offset = 0)
        total shouldBe 3L
        rows.map { it.id } shouldContainExactly listOf(c.id, b.id, a.id)

        // The page window (limit/offset) trims to the middle row while total stays the full count.
        val (windowed, windowTotal) = awards.listAwards(limit = 1, offset = 1)
        windowTotal shouldBe 3L
        windowed.map { it.id } shouldContainExactly listOf(element = b.id)
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

    @Test
    fun `listActiveByUser returns only this user's active in-window awards soonest-expiry-first (#448)`() {
        val user = newUser(uid = "p1")
        val other = newUser(uid = "p2")
        val now = LocalDateTime.of(2026, 6, 1, 0, 0)

        // Two in-window active awards for the user — the one expiring sooner sorts first.
        val laterExpiry = awards.award(write = write(userId = user, validFrom = now.minusMonths(1), validUntil = now.plusMonths(6)))
        val soonerExpiry = awards.award(write = write(userId = user, validFrom = now.minusMonths(1), validUntil = now.plusMonths(1)))
        // Expired and future awards for the user drop out.
        awards.award(write = write(userId = user, validFrom = now.minusMonths(6), validUntil = now.minusMonths(3)))
        awards.award(write = write(userId = user, validFrom = now.plusMonths(1), validUntil = now.plusMonths(6)))
        // A revoked award drops out.
        val revoked = awards.award(write = write(userId = user, validFrom = now.minusMonths(1), validUntil = now.plusMonths(2)))
        awards.revoke(awardId = revoked.id, revokedBy = null, reason = null, revokedAt = now)
        // Another user's in-window award never appears.
        awards.award(write = write(userId = other, validFrom = now.minusMonths(1), validUntil = now.plusMonths(1)))

        awards.listActiveByUser(userId = user, asOf = now).map { it.id } shouldContainExactly listOf(soonerExpiry.id, laterExpiry.id)
    }

    @Test
    fun `a manual award has a null match link that round-trips (#448)`() {
        // Manual grants carry no match id (the match FK stays null). The finalize path's non-null match
        // link is exercised end-to-end in EventFinalizeAwarderTest, where a real match row exists to
        // satisfy the FK. Here we just confirm the null default round-trips through insert and read.
        val user = newUser(uid = "p1")
        val award = awards.award(write = write(userId = user))
        award.matchId.shouldBeNull()
        awards.findById(id = award.id)!!.matchId.shouldBeNull()
    }
}
