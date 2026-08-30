// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AccountLinkStatus
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAwardWrite
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.model.linkStatus
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Generalized admin account-merge (#643): the survivor keeps its own rating + points (no recompute), all
 * participation/membership moves, the survivor keeps the best available login, and the retired account is
 * retired as a "merged → survivor" card. Covers the login re-link case matrix + the selective absorb.
 */
class AccountMergeServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingAssembler()
    private val matches = MatchRepository()
    private val points = RankingPointRepository()
    private val service = DuplicateService(users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        provider: AuthProvider = AuthProvider.PASSWORD,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = provider, providerUid = "$provider:$uid", isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                    capabilities = roles,
                ),
        ).toDomain()

    private fun placeholder(displayName: String): User =
        users.createPlaceholder(
            command =
                org.skopeo.domain.model.CreatePlaceholderCommand(displayName = displayName, sex = "Male", dateOfBirth = null),
        ).toDomain()

    private fun admin(uid: String = "root") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun fixture(
        u1: UUID,
        u2: UUID,
    ) = matches.createFixture(
        command =
            CreateFixtureCommand(
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = LocalDate.of(2026, 1, 1),
                team1UserIds = listOf(element = u1),
                team2UserIds = listOf(element = u2),
                team1Name = "T1",
                team2Name = "T2",
                createdBy = u1,
            ),
    ).toDomain()

    private fun award(userId: UUID) =
        points.award(
            write =
                RankingPointAwardWrite(
                    userId = userId,
                    points = BigDecimal("100"),
                    pointClass = PointClass.ANNUAL_TOURNAMENT,
                    sourceType = PointSourceType.INTERNAL,
                    sourceId = null,
                    band = "4.0",
                    sex = "Male",
                    reason = null,
                    validFrom = LocalDateTime.now().minusDays(1),
                    validUntil = LocalDateTime.now().plusMonths(6),
                    status = AwardStatus.ACTIVE,
                    revokesAwardId = null,
                    grantedBy = null,
                    awardedAt = LocalDateTime.now(),
                ),
        )

    @Test
    fun `merge moves participation and keeps the survivor's own rating and points, leaving the retired's orphaned`() {
        admin()
        val survivor = provisionUser(uid = "survivor")
        val retired = provisionUser(uid = "retired")
        val opponent = provisionUser(uid = "opp")
        // Both accounts have their own rating, points, and a match.
        ratings.setRating(userId = survivor.id, rating = BigDecimal("4.2"), level = "4.0")
        ratings.setRating(userId = retired.id, rating = BigDecimal("3.1"), level = "3.0")
        val survivorAward = award(userId = survivor.id)
        val retiredAward = award(userId = retired.id)
        fixture(u1 = survivor.id, u2 = opponent.id)
        fixture(u1 = retired.id, u2 = opponent.id)

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "same phone",
            ).shouldBeRight()

        // The survivor keeps its OWN rating (no recompute from the absorbed match) and its OWN points.
        ratings.findCurrentRating(userId = survivor.id).shouldNotBeNull().currentRating.toPlainString() shouldBe "4.200000"
        points.findById(id = survivorAward.id).shouldNotBeNull().userId shouldBe survivor.id
        // The retired's rating + points are NOT moved — they stay on the retired (now inactive) row.
        ratings.findCurrentRating(userId = retired.id).shouldNotBeNull().currentRating.toPlainString() shouldBe "3.100000"
        points.findById(id = retiredAward.id).shouldNotBeNull().userId shouldBe retired.id
        // Participation DID move: the survivor now carries the retired's match too; the retired has none.
        users.hasMatchParticipation(userId = survivor.id).shouldBeTrue()
        users.hasMatchParticipation(userId = retired.id).shouldBeFalse()
    }

    @Test
    fun `merge retires the merged-away account as a merged-to-survivor card, not a plain delete`() {
        admin()
        val survivor = provisionUser(uid = "survivor")
        val retired = provisionUser(uid = "retired")

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "ok")
            .shouldBeRight()

        val retiredAfter = users.findById(id = retired.id).shouldBeRight().toDomain()
        retiredAfter.isActive.shouldBeFalse()
        retiredAfter.canonicalUserId shouldBe survivor.id
        // canonical pointer set => a "merged" card, NOT a soft-delete.
        retiredAfter.isDeleted().shouldBeFalse()
    }

    @Test
    fun `both linked - the survivor inherits the retired account's accessible login`() {
        admin()
        val survivor = provisionUser(uid = "survivor", provider = AuthProvider.GOOGLE)
        val retired = provisionUser(uid = "retired", provider = AuthProvider.FACEBOOK)

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "ok")
            .shouldBeRight()

        val survivorAfter = users.findById(id = survivor.id).shouldBeRight().toDomain()
        // The survivor now logs in with the retired account's Firebase uid + provider (uniqueness respected).
        survivorAfter.firebaseUid shouldBe "retired"
        survivorAfter.linkStatus() shouldBe AccountLinkStatus.FACEBOOK
        // The retired account has been freed of its login.
        val retiredAfter = users.findById(id = retired.id).shouldBeRight().toDomain()
        retiredAfter.firebaseUid.shouldBeNull()
        retiredAfter.linkStatus() shouldBe AccountLinkStatus.NONE
        // The login actually resolves to the survivor now.
        users.findByFirebaseUid(firebaseUid = "retired").shouldNotBeNull().user.id shouldBe survivor.id
    }

    @Test
    fun `placeholder survivor with a linked retired - the login moves onto the placeholder and it stops being one`() {
        admin()
        val survivor = placeholder(displayName = "Ghost")
        val retired = provisionUser(uid = "retired", provider = AuthProvider.GOOGLE)
        survivor.linkStatus() shouldBe AccountLinkStatus.NONE

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "ok")
            .shouldBeRight()

        val survivorAfter = users.findById(id = survivor.id).shouldBeRight().toDomain()
        survivorAfter.firebaseUid shouldBe "retired"
        survivorAfter.linkStatus() shouldBe AccountLinkStatus.GOOGLE
        survivorAfter.placeholder.shouldBeFalse()
        users.findByFirebaseUid(firebaseUid = "retired").shouldNotBeNull().user.id shouldBe survivor.id
    }

    @Test
    fun `neither linked - no login step and the survivor stays a claimable placeholder`() {
        admin()
        val survivor = placeholder(displayName = "SurvivorGhost")
        val retired = placeholder(displayName = "RetiredGhost")

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "ok")
            .shouldBeRight()

        val survivorAfter = users.findById(id = survivor.id).shouldBeRight().toDomain()
        survivorAfter.firebaseUid.shouldBeNull()
        survivorAfter.placeholder.shouldBeTrue()
        survivorAfter.linkStatus() shouldBe AccountLinkStatus.NONE
    }

    @Test
    fun `linked survivor with an unlinked retired - the survivor keeps its own login (no-op transfer)`() {
        admin()
        val survivor = provisionUser(uid = "survivor", provider = AuthProvider.GOOGLE)
        val retired = placeholder(displayName = "Ghost")

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "ok")
            .shouldBeRight()

        val survivorAfter = users.findById(id = survivor.id).shouldBeRight().toDomain()
        survivorAfter.firebaseUid shouldBe "survivor"
        survivorAfter.linkStatus() shouldBe AccountLinkStatus.GOOGLE
    }

    @Test
    fun `merge records an audit entry with the moved-records summary, login flag, and verification note`() {
        admin()
        val survivor = provisionUser(uid = "survivor")
        val retired = provisionUser(uid = "retired", provider = AuthProvider.GOOGLE)

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "matched government ID",
            ).shouldBeRight()

        val audit = AuditRepository()
        val (entries, total) = audit.list(actions = listOf(element = AuditAction.USER_ACCOUNTS_MERGED), limit = 10, offset = 0)
        total shouldBe 1L
        val details = entries.single().details
        details["survivorUserId"] shouldBe survivor.id.toString()
        details["retiredUserId"] shouldBe retired.id.toString()
        details["loginTransferred"] shouldBe "true"
        details["verificationNote"] shouldBe "matched government ID"
    }

    @Test
    fun `merge rejects a blank verification note`() {
        admin()
        val survivor = provisionUser(uid = "survivor")
        val retired = provisionUser(uid = "retired")

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = retired.id, verificationNote = "   ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `merge rejects merging an account into itself`() {
        admin()
        val survivor = provisionUser(uid = "survivor")

        service
            .mergeAccounts(token = token(uid = "root"), survivorId = survivor.id, retiredAccountId = survivor.id, verificationNote = "ok")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `merge is not-found for an unknown account`() {
        admin()
        val survivor = provisionUser(uid = "survivor")

        service
            .mergeAccounts(
                token = token(uid = "root"),
                survivorId = survivor.id,
                retiredAccountId = UUID.randomUUID(),
                verificationNote = "ok",
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `only an admin may merge accounts`() {
        val survivor = provisionUser(uid = "survivor")
        val retired = provisionUser(uid = "retired")

        service
            .mergeAccounts(
                token = token(uid = "survivor"),
                survivorId = survivor.id,
                retiredAccountId = retired.id,
                verificationNote = "ok",
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
