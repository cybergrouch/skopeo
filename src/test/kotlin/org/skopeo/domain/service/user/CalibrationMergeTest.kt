// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.CalibrationService
import org.skopeo.domain.service.rating.RatingService
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

/**
 * Calibration across an account merge (#881).
 *
 * The survivor **inherits** the window, taking the earlier designation — the safer side. A merge moves the
 * retired account's matches onto the survivor, so the survivor's rating becomes answerable for play it did
 * not previously own; suppression can only ever withhold a change, never damage a settled rating, whereas
 * dropping a window would start moving opponents' ratings off a number that is still a guess.
 *
 * Because calibration is derived from the timestamp rather than stored, "inherit the longer window" *is*
 * "keep the earlier timestamp" — which is what makes this a two-line operation rather than a state
 * machine.
 */
class CalibrationMergeTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratingService = RatingService()
    private val calibration = CalibrationService()
    private val service = DuplicateService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun designate(
        raterUid: String,
        userId: UUID,
    ) = ratingService.setRating(token = token(uid = raterUid), userId = userId, value = "4.0").shouldBeRight()

    private fun stampedAt(userId: UUID): LocalDateTime? =
        transaction {
            UserRatingsTable
                .selectAll()
                .where { UserRatingsTable.userId eq userId }
                .single()[UserRatingsTable.calibrationStartedAt]
        }

    private fun setStamp(
        userId: UUID,
        at: LocalDateTime?,
    ) {
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationStartedAt] = at
            }
        }
    }

    @Test
    fun `a settled survivor inherits the retired account's calibration window (#881)`() {
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val survivor = provision(uid = "survivor")
        val retired = provision(uid = "retired")
        designate(raterUid = "rater", userId = survivor.id)
        designate(raterUid = "rater", userId = retired.id)
        // The survivor is settled; the retired account is mid-calibration.
        setStamp(userId = survivor.id, at = null)
        calibration.isCalibrating(userId = survivor.id).shouldBeFalse()

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "same person, confirmed by phone",
            ).shouldBeRight()

        // Inherited: the retired account's matches now belong to the survivor, so the survivor's rating is
        // answerable for play it did not own before. Suppression is the conservative outcome.
        stampedAt(userId = survivor.id).shouldNotBeNull()
        calibration.isCalibrating(userId = survivor.id).shouldBeTrue()
    }

    @Test
    fun `the survivor keeps its own window when that one is earlier (#881)`() {
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val survivor = provision(uid = "survivor")
        val retired = provision(uid = "retired")
        designate(raterUid = "rater", userId = survivor.id)
        designate(raterUid = "rater", userId = retired.id)
        setStamp(userId = survivor.id, at = LocalDateTime.now().minusDays(30))
        setStamp(userId = retired.id, at = LocalDateTime.now())
        // Read the stamp back rather than remembering what was written: Postgres TIMESTAMP truncates
        // LocalDateTime's nanoseconds to microseconds, so the stored value is NOT equal to the literal
        // that produced it. Comparing against the persisted form makes this assertion about whether the
        // merge moved the window, which is the actual claim.
        val earlier = stampedAt(userId = survivor.id).shouldNotBeNull()

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "same person",
            ).shouldBeRight()

        // Never moves the window LATER — that would shorten a calibration in progress, which is the one
        // direction that could start moving opponents' ratings sooner than intended.
        stampedAt(userId = survivor.id).shouldNotBeNull() shouldBe earlier
    }

    @Test
    fun `a merge from a settled retired account leaves the survivor untouched (#881)`() {
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val survivor = provision(uid = "survivor")
        val retired = provision(uid = "retired")
        designate(raterUid = "rater", userId = survivor.id)
        designate(raterUid = "rater", userId = retired.id)
        setStamp(userId = survivor.id, at = null)
        setStamp(userId = retired.id, at = null)

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "same person",
            ).shouldBeRight()

        // Nothing to inherit, so nothing invented: merging two settled accounts must not put anyone into
        // calibration.
        stampedAt(userId = survivor.id).shouldBeNull()
        calibration.isCalibrating(userId = survivor.id).shouldBeFalse()
    }
}
