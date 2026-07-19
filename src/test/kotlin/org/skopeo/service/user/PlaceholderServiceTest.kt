// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
import org.skopeo.model.displayName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.PlaceholderClaimCodeRepository
import org.skopeo.repository.PlaceholderClaimCodesTable
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingHistoryTable
import org.skopeo.repository.UserRepository
import org.skopeo.repository.UsersTable
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PlaceholderServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val claimCodes = PlaceholderClaimCodeRepository()
    private val ratings = RatingRepository()
    private val service = PlaceholderService(users = users, claimCodes = claimCodes)

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
                    sex = "Male",
                    capabilities = roles,
                ),
        )

    private fun admin(uid: String = "root") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun host(uid: String = "host") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.HOST))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    // ---- Create ----

    @Test
    fun `a host creates a login-less PLAYER-only placeholder that appears as a player`() {
        host(uid = "host")

        val created =
            service
                .createPlaceholder(
                    token = token(uid = "host"),
                    displayName = "Backlog Player",
                    sex = "Male",
                    dateOfBirth = LocalDate.of(1990, 1, 2),
                )
                .shouldBeRight()

        created.placeholder.shouldBeTrue()
        created.firebaseUid shouldBe null
        created.capabilities shouldBe setOf(element = Capability.PLAYER)
        created.displayName() shouldBe "Backlog Player"
        created.dateOfBirth shouldBe LocalDate.of(1990, 1, 2)
        // Surfaces in the management list.
        service.listPlaceholders(token = token(uid = "host")).shouldBeRight().map { it.id } shouldContain created.id
    }

    @Test
    fun `creating a placeholder requires a match-management capability`() {
        provisionUser(uid = "plain")

        service
            .createPlaceholder(token = token(uid = "plain"), displayName = "Nope", sex = "Male")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `creating a placeholder rejects an invalid sex`() {
        host(uid = "host")

        service
            .createPlaceholder(token = token(uid = "host"), displayName = "Bad Sex", sex = "Other")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `creating a placeholder rejects a blank display name`() {
        host(uid = "host")

        service
            .createPlaceholder(token = token(uid = "host"), displayName = "   ", sex = "Male")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    // ---- Generate claim code ----

    @Test
    fun `an admin generates a random, hashed, one-time claim code`() {
        admin(uid = "root")
        val placeholder = service.createPlaceholder(token = token(uid = "root"), displayName = "Dummy", sex = "Male").shouldBeRight()

        val generated = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()

        generated.plaintext.shouldNotBeNull()
        generated.placeholderPublicCode shouldBe placeholder.publicCode
        // The stored hash is the SHA-256 of the plaintext (not the plaintext itself).
        generated.code.codeHash shouldBe ClaimCodeCrypto.hash(plaintext = generated.plaintext)
        generated.code.codeHash shouldNotBe generated.plaintext
    }

    @Test
    fun `re-issuing a claim code supersedes the prior active one`() {
        admin(uid = "root")
        val placeholder = service.createPlaceholder(token = token(uid = "root"), displayName = "Dummy", sex = "Male").shouldBeRight()

        val first = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()
        service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()

        // The first code no longer resolves as ACTIVE (superseded on re-issue).
        claimCodes.findActiveByHash(codeHash = first.code.codeHash) shouldBe null
    }

    @Test
    fun `only an admin generates a claim code`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()

        service
            .generateClaimCode(token = token(uid = "host"), placeholderId = placeholder.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a claim code cannot be generated for a non-placeholder user`() {
        admin(uid = "root")
        val real = provisionUser(uid = "real")

        service
            .generateClaimCode(token = token(uid = "root"), placeholderId = real.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a claim code cannot be generated for an already-claimed placeholder`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        val claimant = provisionUser(uid = "claimant")
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()
        service.claim(token = token(uid = "claimant"), code = code.plaintext).shouldBeRight()
        claimant.id shouldNotBe placeholder.id

        service
            .generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    // ---- Claim (success) ----

    @Test
    fun `an empty account claims a placeholder — history transfers, placeholder retired, code consumed, audited`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        // The placeholder accrued a rating history row.
        ratings.setRating(userId = placeholder.id, rating = BigDecimal("3.500000"), level = "3.5")
        appendRatingHistory(userId = placeholder.id)
        val claimant = provisionUser(uid = "claimant")

        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()

        val merged = service.claim(token = token(uid = "claimant"), code = code.plaintext).shouldBeRight()

        merged.id shouldBe claimant.id
        // History moved to the claimant.
        users.hasRatingHistory(userId = claimant.id).shouldBeTrue()
        users.hasRatingHistory(userId = placeholder.id).shouldBeFalse()
        // Placeholder retired via the canonical-link pattern.
        val retired = users.findById(id = placeholder.id).shouldBeRight()
        retired.isActive.shouldBeFalse()
        retired.canonicalUserId shouldBe claimant.id
        retired.claimedBy shouldBe claimant.id
        retired.claimedAt.shouldNotBeNull()
        // Code consumed — a second attempt fails.
        service.claim(token = token(uid = "claimant"), code = code.plaintext).shouldBeLeft()
        // Audited against the claiming user.
        AuditRepository()
            .list(actions = listOf(element = AuditAction.PLACEHOLDER_CLAIMED), limit = 10, offset = 0)
            .first
            .map { it.entityId } shouldContain claimant.id
    }

    // ---- Claim (rejections) ----

    @Test
    fun `claim rejects an unknown code`() {
        provisionUser(uid = "claimant")

        service
            .claim(token = token(uid = "claimant"), code = "TOTALLYBOGUSCODE0000")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `claim rejects a caller who has not signed up`() {
        // No user provisioned for this uid — the caller cannot be resolved from the token.
        service
            .claim(token = token(uid = "ghost"), code = "ANYCODE0000")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `claim rejects a blank code`() {
        provisionUser(uid = "claimant")

        service
            .claim(token = token(uid = "claimant"), code = "   ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `claim rejects an expired code`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        provisionUser(uid = "claimant")
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()
        // Backdate the code's expiry so it is ACTIVE but no longer usable.
        expireCode(codeHash = code.code.codeHash)

        service
            .claim(token = token(uid = "claimant"), code = code.plaintext)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `claim rejects a non-empty caller account`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        val claimant = provisionUser(uid = "claimant")
        // The claimant already has rating history → not empty.
        appendRatingHistory(userId = claimant.id)
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()

        service
            .claim(token = token(uid = "claimant"), code = code.plaintext)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `claim rejects an already-consumed code`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        val first = provisionUser(uid = "first")
        val second = provisionUser(uid = "second")
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()

        service.claim(token = token(uid = "first"), code = code.plaintext).shouldBeRight()

        // The code is spent; a different empty account cannot reuse it.
        service.claim(token = token(uid = "second"), code = code.plaintext).shouldBeLeft()
        second.id shouldNotBe first.id
    }

    @Test
    fun `claim rejects an already-claimed placeholder`() {
        admin(uid = "root")
        host(uid = "host")
        val placeholder = service.createPlaceholder(token = token(uid = "host"), displayName = "Dummy", sex = "Male").shouldBeRight()
        provisionUser(uid = "claimant")
        val other = provisionUser(uid = "other")
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = placeholder.id).shouldBeRight()
        // Construct the (normally-unreachable) already-claimed state — a canonical link set while the code is
        // still active — so the claim hits the already-claimed guard.
        markClaimed(userId = placeholder.id, canonicalId = other.id)

        service
            .claim(token = token(uid = "claimant"), code = code.plaintext)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a placeholder cannot claim another placeholder`() {
        admin(uid = "root")
        host(uid = "host")
        val target = service.createPlaceholder(token = token(uid = "host"), displayName = "Target", sex = "Male").shouldBeRight()
        val code = service.generateClaimCode(token = token(uid = "root"), placeholderId = target.id).shouldBeRight()
        // A placeholder has no firebase_uid, so it can never present a token — assert the guard holds even
        // if one somehow did by making the caller a placeholder-typed account with a uid.
        val placeholderCaller = provisionUser(uid = "phcaller")
        markAsPlaceholder(userId = placeholderCaller.id)

        service
            .claim(token = token(uid = "phcaller"), code = code.plaintext)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    /** Append a minimal rating-history row for [userId] so the account counts as "non-empty" (#496). */
    private fun appendRatingHistory(userId: UUID) {
        transaction {
            UserRatingHistoryTable.insert {
                it[UserRatingHistoryTable.userId] = userId
                it[matchId] = null
                it[previousRating] = BigDecimal("3.000000")
                it[newRating] = BigDecimal("3.500000")
                it[ratingChange] = BigDecimal("0.500000")
                it[calculatedAt] = LocalDateTime.now()
            }
        }
    }

    /** Flip [userId] to a placeholder row (test-only) to exercise the caller-is-placeholder guard. */
    private fun markAsPlaceholder(userId: UUID) {
        transaction {
            UsersTable.update(where = { UsersTable.id eq userId }) { it[placeholder] = true }
        }
    }

    /** Point [userId] at a canonical account (test-only) to construct the already-claimed guard state. */
    private fun markClaimed(
        userId: UUID,
        canonicalId: UUID,
    ) {
        transaction {
            UsersTable.update(where = { UsersTable.id eq userId }) { it[canonicalUserId] = canonicalId }
        }
    }

    /** Backdate the ACTIVE code with [codeHash] so it is expired but not yet superseded (#496 expiry guard). */
    private fun expireCode(codeHash: String) {
        transaction {
            PlaceholderClaimCodesTable.update(where = { PlaceholderClaimCodesTable.codeHash eq codeHash }) {
                it[expiresAt] = LocalDateTime.now().minusDays(1)
            }
        }
    }
}
