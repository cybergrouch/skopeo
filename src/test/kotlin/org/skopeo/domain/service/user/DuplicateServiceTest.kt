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
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
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
    private val ratings = RatingAssembler()
    private val matches = MatchRepository()
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
        ).toDomain()

    private fun admin(uid: String = "root") = provisionUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    // A singles fixture the given players contested — enough to give a user match participation (team_users).
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

    @Test
    fun `replaceAccount imports the old account's rating and matches into an empty canonical, then deletes the old (#124)`() {
        admin()
        val canonical = provisionUser(uid = "new")
        val old = provisionUser(uid = "old")
        val opponent = provisionUser(uid = "opp")
        // The OLD account has a rating and a match; the NEW (canonical) account is empty.
        ratings.setRating(userId = old.id, rating = BigDecimal("4.2"), level = "4.0")
        fixture(u1 = old.id, u2 = opponent.id)
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = old.id))
            .shouldBeRight()

        service.replaceAccount(token = token(uid = "root"), canonicalId = canonical.id, duplicateId = old.id).shouldBeRight()

        // The rating (+ match participation) now belong to the canonical; the old account is emptied.
        ratings.findCurrentRating(userId = canonical.id).shouldNotBeNull().currentRating.toPlainString() shouldBe "4.200000"
        ratings.findCurrentRating(userId = old.id).shouldBeNull()
        users.hasMatchParticipation(userId = canonical.id).shouldBeTrue()
        users.hasMatchParticipation(userId = old.id).shouldBeFalse()
        // The old account is deleted (#518 state: inactive + no canonical pointer), not left a merged duplicate.
        val deletedOld = users.findById(id = old.id).shouldBeRight().toDomain()
        deletedOld.isActive.shouldBeFalse()
        deletedOld.isDeleted().shouldBeTrue()
    }

    @Test
    fun `records an audit entry for the replace (#124)`() {
        admin()
        val canonical = provisionUser(uid = "new")
        val old = provisionUser(uid = "old")
        ratings.setRating(userId = old.id, rating = BigDecimal("4.2"), level = "4.0")
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = old.id))
            .shouldBeRight()

        service.replaceAccount(token = token(uid = "root"), canonicalId = canonical.id, duplicateId = old.id).shouldBeRight()

        AuditRepository()
            .list(actions = listOf(element = AuditAction.USER_ACCOUNT_REPLACED), limit = 10, offset = 0)
            .second shouldBe 1L
    }

    @Test
    fun `rejects replacing into a canonical that already has its own rating or matches (#124)`() {
        admin()
        val canonical = provisionUser(uid = "new")
        val old = provisionUser(uid = "old")
        val opponent = provisionUser(uid = "opp")
        // The canonical is NOT empty — it played its own match.
        fixture(u1 = canonical.id, u2 = opponent.id)
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = canonical.id, duplicateIds = listOf(element = old.id))
            .shouldBeRight()

        service
            .replaceAccount(token = token(uid = "root"), canonicalId = canonical.id, duplicateId = old.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `rejects replacing with a user that is not a duplicate of the canonical (#124)`() {
        admin()
        val canonical = provisionUser(uid = "new")
        val other = provisionUser(uid = "other") // never marked as a duplicate of canonical

        service
            .replaceAccount(token = token(uid = "root"), canonicalId = canonical.id, duplicateId = other.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `rejects replacing when the canonical is itself a duplicate (#124)`() {
        admin()
        val trueAccount = provisionUser(uid = "true")
        val notCanonical = provisionUser(uid = "notcanon")
        val old = provisionUser(uid = "old")
        // notCanonical is itself a duplicate of trueAccount, so it cannot be a replace target.
        service
            .markDuplicates(token = token(uid = "root"), canonicalId = trueAccount.id, duplicateIds = listOf(element = notCanonical.id))
            .shouldBeRight()

        service
            .replaceAccount(token = token(uid = "root"), canonicalId = notCanonical.id, duplicateId = old.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a non-admin cannot replace (#124)`() {
        val canonical = provisionUser(uid = "new")
        val old = provisionUser(uid = "old")

        service
            .replaceAccount(token = token(uid = "old"), canonicalId = canonical.id, duplicateId = old.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

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

        result.map { it.id }.toSet() shouldBe setOf(dup1.id.toString(), dup2.id.toString())
        listOf(dup1.id, dup2.id).forEach { id ->
            users.findById(id = id).shouldBeRight().toDomain().let {
                it.isActive.shouldBeFalse()
                it.canonicalUserId shouldBe canonical.id
            }
        }
        // The canonical itself is untouched.
        users.findById(id = canonical.id).shouldBeRight().toDomain().isActive.shouldBeTrue()

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

        users.findById(id = dup.id).shouldBeRight().toDomain().let {
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

        service.duplicatesOf(token = token(uid = "root"), canonicalId = canonical.id).shouldBeRight().single().id shouldBe dup.id.toString()
    }
}
