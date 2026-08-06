// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.BandRelation
import org.skopeo.common.contract.OpenPlayMarginPoints
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.PointsConfigRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase

class PointsConfigServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val configs = PointsConfigRepository()
    private val service = PointsConfigService(configs = configs, users = users)

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
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun sampleOpenPlay(validityDays: Int = 90): OpenPlayPointsConfig =
        OpenPlayPointsConfig(
            maxMargin = 2,
            rows =
                listOf(
                    OpenPlayMarginPoints(relation = BandRelation.EQUAL, margin = 1, winnerPoints = 2, loserPoints = 0),
                    OpenPlayMarginPoints(relation = BandRelation.FAVORITE, margin = 1, winnerPoints = 3, loserPoints = 1),
                    OpenPlayMarginPoints(relation = BandRelation.UPSET, margin = 1, winnerPoints = 5, loserPoints = -2),
                    OpenPlayMarginPoints(relation = BandRelation.EQUAL, margin = 2, winnerPoints = 5, loserPoints = 0),
                    OpenPlayMarginPoints(relation = BandRelation.FAVORITE, margin = 2, winnerPoints = 8, loserPoints = 0),
                    OpenPlayMarginPoints(relation = BandRelation.UPSET, margin = 2, winnerPoints = 13, loserPoints = -3),
                ),
            validityDays = validityDays,
        )

    @Test
    fun `open-play defaults to the seeded schedule with no provenance when unset`() {
        val stored = service.getOpenPlay()
        stored.value shouldBe OpenPlayPointsConfig.DEFAULT
        stored.updatedBy.shouldBeNull()
        stored.updatedAt.shouldBeNull()
    }

    @Test
    fun `an admin sets then updates the open-play schedule, and reads it back with provenance`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // First write inserts; second write updates (exercises the repository upsert's both arms).
        service.setOpenPlay(token = token(uid = "admin"), config = sampleOpenPlay(validityDays = 90)).shouldBeRight()
        val updated = service.setOpenPlay(token = token(uid = "admin"), config = sampleOpenPlay(validityDays = 120)).shouldBeRight()
        updated.config.validityDays shouldBe 120
        updated.updatedBy shouldBe admin.id.toString()

        val read = service.getOpenPlay()
        read.value.validityDays shouldBe 120
        read.value.cell(relation = BandRelation.UPSET, margin = 2).winnerPoints shouldBe 13
        read.updatedBy.shouldNotBeNull()
    }

    @Test
    fun `a non-admin cannot set the open-play schedule`() {
        provision(uid = "player")
        service.setOpenPlay(token = token(uid = "player"), config = sampleOpenPlay())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An unknown caller is likewise forbidden.
        service.setOpenPlay(token = token(uid = "ghost"), config = sampleOpenPlay())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an invalid open-play schedule is rejected as Validation`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setOpenPlay(token = token(uid = "admin"), config = sampleOpenPlay().copy(validityDays = 0))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a corrupt stored open-play schedule falls back to the default`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        configs.upsert(key = "open_play", value = "not-json", updatedBy = admin.id)
        service.getOpenPlay().value shouldBe OpenPlayPointsConfig.DEFAULT
    }

    @Test
    fun `tournament defaults, then an admin sets it and a non-admin is forbidden`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "player")
        service.getTournament().value shouldBe TournamentPointsConfig.DEFAULT

        val config = TournamentPointsConfig(sanctioned = listOf(100, 70, 50, 35), unsanctioned = listOf(50, 35, 25, 18), validityDays = 365)
        val saved = service.setTournament(token = token(uid = "admin"), config = config).shouldBeRight()
        saved.config.sanctioned shouldBe listOf(100, 70, 50, 35)
        saved.updatedBy shouldBe admin.id.toString()
        service.getTournament().value.unsanctioned shouldBe listOf(50, 35, 25, 18)

        service.setTournament(token = token(uid = "player"), config = config)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a tournament schedule with the wrong number of places is rejected as Validation`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val bad = TournamentPointsConfig(sanctioned = listOf(80, 60, 40), unsanctioned = listOf(40, 30, 20, 15), validityDays = 365)
        service.setTournament(token = token(uid = "admin"), config = bad)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }
}
