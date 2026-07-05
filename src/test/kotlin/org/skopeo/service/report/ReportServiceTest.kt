// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.report

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.report.BandHopReportResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ReportServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val service = ReportService(users = users, ratings = ratings)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
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

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    /** Provision a rated player whose current band is [currentLevel]. */
    private fun ratedPlayer(
        uid: String,
        currentLevel: String,
    ): User {
        val user = provision(uid = uid)
        ratings.setRating(userId = user.id, rating = currentLevel.toBigDecimal(), level = currentLevel, confidence = BigDecimal("0.5"))
        return user
    }

    /** Append a band change at [at] (ISO date-time), moving the player [from] band [to] band. */
    private fun change(
        userId: UUID,
        at: String,
        from: String,
        to: String,
    ) {
        ratings.appendHistory(
            write =
                RatingHistoryWrite(
                    userId = userId,
                    matchId = null,
                    previousRating = from.toBigDecimal(),
                    newRating = to.toBigDecimal(),
                    ratingChange = to.toBigDecimal() - from.toBigDecimal(),
                    percentChange = null,
                    previousLevel = from,
                    newLevel = to,
                    levelChanged = from != to,
                    breakdown = null,
                    calculatedAt = LocalDateTime.parse(at),
                ),
        )
    }

    private fun report(admin: String = "admin") =
        service.bandHops(
            token = token(uid = admin),
            startDate = LocalDate.parse("2026-03-01"),
            endDate = LocalDate.parse("2026-03-31"),
        )

    @Test
    fun `non-admin is forbidden and an inverted range is rejected`() {
        provision(uid = "player")
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        report(admin = "player").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // A token that maps to no account is likewise forbidden (the caller can't be resolved).
        report(admin = "ghost").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .bandHops(token = token(uid = "admin"), startDate = LocalDate.parse("2026-03-31"), endDate = LocalDate.parse("2026-03-01"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `buckets players by net band movement over the window, counting stayers as the majority`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        // Stayed: no rating changes at all.
        ratedPlayer(uid = "stayer", currentLevel = "3.0")
        // Stayed: churned within the window but ended where it began (net zero).
        val roundtrip = ratedPlayer(uid = "roundtrip", currentLevel = "3.0")
        change(userId = roundtrip.id, at = "2026-03-05T10:00", from = "3.0", to = "3.5")
        change(userId = roundtrip.id, at = "2026-03-15T10:00", from = "3.5", to = "3.0")
        // Stayed: moved a band BEFORE the window; unchanged during it.
        val preMover = ratedPlayer(uid = "preMover", currentLevel = "3.5")
        change(userId = preMover.id, at = "2026-02-01T10:00", from = "3.0", to = "3.5")
        // Jumped up two bands within the window (3.0 -> 4.0).
        val jumper = ratedPlayer(uid = "jumper", currentLevel = "4.0")
        change(userId = jumper.id, at = "2026-03-10T10:00", from = "3.0", to = "3.5")
        change(userId = jumper.id, at = "2026-03-20T10:00", from = "3.5", to = "4.0")
        // A change AFTER the window close is excluded from the closing band (still 4.0, not 4.5).
        change(userId = jumper.id, at = "2026-04-05T10:00", from = "4.0", to = "4.5")
        // Dropped one band within the window (direction-agnostic): entered at 3.5, closed at 3.0.
        val dropper = ratedPlayer(uid = "dropper", currentLevel = "3.0")
        change(userId = dropper.id, at = "2026-02-10T10:00", from = "3.0", to = "3.5")
        change(userId = dropper.id, at = "2026-03-12T10:00", from = "3.5", to = "3.0")

        val result: BandHopReportResponse = report().shouldBeRight()

        result.totalPlayers shouldBe 5
        result.stayedCount shouldBe 3
        result.jumpedCount shouldBe 2
        result.buckets.associate { it.hopDistance to it.count } shouldBe mapOf(0 to 3, 1 to 1, 2 to 1)

        val jumped = result.buckets.single { it.hopDistance == 2 }.users.single()
        jumped.publicCode shouldBe jumper.publicCode
        jumped.fromBand shouldBe "3.0"
        jumped.toBand shouldBe "4.0"

        val dropped = result.buckets.single { it.hopDistance == 1 }.users.single()
        dropped.fromBand shouldBe "3.5"
        dropped.toBand shouldBe "3.0"
    }

    @Test
    fun `a player whose band cannot be determined is skipped`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        ratedPlayer(uid = "banded", currentLevel = "3.0")
        // A rating row with no band label (and no history) can't be placed on the band scale.
        val user = provision(uid = "unbanded")
        ratings.setRating(userId = user.id, rating = BigDecimal("3.2"), level = null, confidence = BigDecimal("0.5"))

        val result = report().shouldBeRight()

        result.totalPlayers shouldBe 1
        result.buckets.single { it.hopDistance == 0 }.users.single().publicCode shouldBe
            users.findByFirebaseUid(firebaseUid = "banded")!!.publicCode
    }

    @Test
    fun `a history row without a band label falls back to the previous band`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val user = ratedPlayer(uid = "u", currentLevel = "3.0")
        // A change row can carry a null newLevel (nullable column); it must not null out the resolved band.
        ratings.appendHistory(
            write =
                RatingHistoryWrite(
                    userId = user.id,
                    matchId = null,
                    previousRating = BigDecimal("3.0"),
                    newRating = BigDecimal("3.2"),
                    ratingChange = BigDecimal("0.2"),
                    percentChange = null,
                    previousLevel = "3.0",
                    newLevel = null,
                    levelChanged = false,
                    breakdown = null,
                    calculatedAt = LocalDateTime.parse("2026-03-10T10:00"),
                ),
        )

        val result = report().shouldBeRight()

        result.totalPlayers shouldBe 1
        result.buckets.single { it.hopDistance == 0 }.users.single().toBand shouldBe "3.0"
    }

    @Test
    fun `a player rated but never recalculated is reported as having stayed`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val user = ratedPlayer(uid = "solo", currentLevel = "4.5")

        val result = report().shouldBeRight()

        result.totalPlayers shouldBe 1
        val stayed = result.buckets.single { it.hopDistance == 0 }.users.single()
        stayed.publicCode shouldBe user.publicCode
        stayed.fromBand shouldBe "4.5"
        stayed.toBand shouldBe "4.5"
    }
}
