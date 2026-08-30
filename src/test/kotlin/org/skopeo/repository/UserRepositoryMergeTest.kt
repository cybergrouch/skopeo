// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.CreatePlaceholderCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Repository-level checks for the account-merge primitive (#643): the selective absorb (participation moves,
 * rating stays), the per-table moved counts, and the login-transfer ordering that must respect the
 * `users.firebase_uid` and `uq_identity_provider_uid` UNIQUE constraints.
 */
class UserRepositoryMergeTest {
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

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(
        uid: String,
        provider: AuthProvider = AuthProvider.GOOGLE,
    ): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = provider, providerUid = "$provider:$uid", isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                ),
        ).user.id

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
    fun `selective absorb moves participation and reports counts, without touching the survivor's rating`() {
        val survivor = user(uid = "survivor")
        val retired = user(uid = "retired")
        val opponent = user(uid = "opp")
        ratings.setRating(userId = survivor, rating = BigDecimal("4.2"), level = "4.0")
        fixture(u1 = retired, u2 = opponent)

        val result = users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = true)

        result.teamMemberships shouldBe 1
        result.loginTransferred shouldBe true
        // The survivor's rating is untouched (the retired match was not re-rated).
        ratings.findCurrentRating(userId = survivor).shouldNotBeNull().currentRating.toPlainString() shouldBe "4.200000"
        users.hasMatchParticipation(userId = survivor) shouldBe true
        users.hasMatchParticipation(userId = retired) shouldBe false
    }

    @Test
    fun `login transfer frees the retired anchors before re-using them, so same-provider merges do not violate UNIQUE`() {
        // Both accounts use the SAME provider (GOOGLE) with different provider_uids — the worst case for
        // uq_identity_provider_uid. The free-then-set ordering must not collide.
        val survivor = user(uid = "survivor", provider = AuthProvider.GOOGLE)
        val retired = user(uid = "retired", provider = AuthProvider.GOOGLE)

        shouldNotThrowAny {
            users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = true)
        }

        val survivorAfter = users.findById(id = survivor).getOrNull()!!.toDomain()
        survivorAfter.firebaseUid?.revealed shouldBe "retired"
        survivorAfter.identities.single().let { identity ->
            identity.provider shouldBe AuthProvider.GOOGLE
            identity.providerUid shouldBe "GOOGLE:retired"
            identity.isPrimary shouldBe true
        }
        // The retired account is freed of its login anchors.
        val retiredAfter = users.findById(id = retired).getOrNull()!!.toDomain()
        retiredAfter.firebaseUid.shouldBeNull()
        retiredAfter.identities shouldBe emptyList()
    }

    @Test
    fun `login transfer is a no-op when the retired account has no login (placeholder retired)`() {
        // transferLogin is requested, but the retired account is a login-less placeholder — the transfer
        // short-circuits, leaving the survivor's own login untouched.
        val survivor = user(uid = "survivor")
        val retired = users.createPlaceholder(command = CreatePlaceholderCommand(displayName = "Ghost", sex = "Male")).toDomain().id

        shouldNotThrowAny {
            users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = true)
        }

        users.findById(id = survivor).getOrNull()!!.toDomain().firebaseUid?.revealed shouldBe "survivor"
        users.findById(id = retired).getOrNull()!!.toDomain().firebaseUid.shouldBeNull()
    }
}
