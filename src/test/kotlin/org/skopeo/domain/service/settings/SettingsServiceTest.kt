// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.SnapshotSource
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.AppSettingsTable
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase

class SettingsServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val settings = AppSettingsRepository()
    private val service = SettingsService(settings = settings, users = users)

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
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    @Test
    fun `standingsSource defaults to RATING when no setting row exists (#146)`() {
        transaction { AppSettingsTable.deleteAll() }
        service.standingsSource() shouldBe SnapshotSource.RATING
        service.getStandingsSource().updatedBy.shouldBeNull()
    }

    @Test
    fun `standingsSource parses a stored RATING value (#146)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "standings_source", value = "RATING", updatedBy = admin.id)
        service.standingsSource() shouldBe SnapshotSource.RATING
    }

    @Test
    fun `standingsSource parses a stored POINTS value (#146)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "standings_source", value = "POINTS", updatedBy = admin.id)
        service.standingsSource() shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `standingsSource falls back to RATING when the stored value is unrecognized (#146)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "standings_source", value = "GRAVEL", updatedBy = admin.id)
        service.standingsSource() shouldBe SnapshotSource.RATING
    }

    @Test
    fun `an admin sets the standings source and the read reflects it (#146)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val set = service.setStandingsSource(token = token(uid = "admin"), source = "POINTS").shouldBeRight()
        set.source shouldBe SnapshotSource.POINTS.name

        service.getStandingsSource().source shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `setting the standings source is case-insensitive and records an audit row (#146)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setStandingsSource(token = token(uid = "admin"), source = "points").shouldBeRight().source shouldBe
            SnapshotSource.POINTS.name

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.SETTINGS_STANDINGS_SOURCE_CHANGED), limit = 10, offset = 0)
            .let { (items, total) ->
                total shouldBe 1L
                items.single().summary shouldBe "Set standings source to POINTS"
            }
    }

    @Test
    fun `a non-admin cannot set the standings source (#146)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.setStandingsSource(token = token(uid = "host"), source = "POINTS")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unprovisioned caller cannot set the standings source (#146)`() {
        service.setStandingsSource(token = token(uid = "ghost"), source = "POINTS")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unknown standings source string is a validation error (#146)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setStandingsSource(token = token(uid = "admin"), source = "GRAVEL")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `facebook login defaults to enabled when no setting row exists (#647)`() {
        service.getFacebookLogin().enabled shouldBe true
    }

    @Test
    fun `facebook login reads a stored false value (#647)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "facebook_login_enabled", value = "false", updatedBy = admin.id)
        service.getFacebookLogin().enabled shouldBe false
    }

    @Test
    fun `facebook login falls back to enabled when the stored value is not boolean (#647)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "facebook_login_enabled", value = "maybe", updatedBy = admin.id)
        service.getFacebookLogin().enabled shouldBe true
    }

    @Test
    fun `an admin disables facebook login and the read reflects it, with an audit entry (#647)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val set = service.setFacebookLogin(token = token(uid = "admin"), enabled = false).shouldBeRight()

        set.enabled shouldBe false
        set.updatedBy.shouldBeInstanceOf<String>()
        service.getFacebookLogin().enabled shouldBe false
        AuditRepository()
            .list(actions = listOf(element = AuditAction.SETTINGS_FACEBOOK_LOGIN_CHANGED), limit = 10, offset = 0)
            .second shouldBe 1L
    }

    @Test
    fun `a non-admin cannot set the facebook login flag (#647)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.setFacebookLogin(token = token(uid = "host"), enabled = false)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `award ranking points defaults to disabled when no setting row exists (#641)`() {
        service.getAwardRankingPoints().enabled shouldBe false
    }

    @Test
    fun `award ranking points reads a stored true value (#641)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "award_ranking_points_enabled", value = "true", updatedBy = admin.id)
        service.getAwardRankingPoints().enabled shouldBe true
    }

    @Test
    fun `award ranking points falls back to disabled when the stored value is not boolean (#641)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "award_ranking_points_enabled", value = "maybe", updatedBy = admin.id)
        service.getAwardRankingPoints().enabled shouldBe false
    }

    @Test
    fun `an admin enables award ranking points and the read reflects it, with an audit entry (#641)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val set = service.setAwardRankingPoints(token = token(uid = "admin"), enabled = true).shouldBeRight()

        set.enabled shouldBe true
        service.getAwardRankingPoints().enabled shouldBe true
        AuditRepository()
            .list(actions = listOf(element = AuditAction.SETTINGS_AWARD_RANKING_POINTS_CHANGED), limit = 10, offset = 0)
            .second shouldBe 1L
    }

    @Test
    fun `a non-admin cannot set the award ranking points flag (#641)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.setAwardRankingPoints(token = token(uid = "host"), enabled = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `hide ranking points defaults to NOT hidden, so the flag has no effect until ticked (#865)`() {
        // The opposite default from award-ranking-points, deliberately: this flag is opt-in suppression,
        // so an unseeded database must behave exactly as it did before the flag existed. Shipping it must
        // change nothing.
        service.getHideRankingPoints().hidden shouldBe false
        service.pointsVisibleTo(viewer = null) shouldBe true
    }

    @Test
    fun `hide ranking points falls back to not hidden when the stored value is not boolean (#865)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        settings.upsert(key = "hide_ranking_points_from_players", value = "maybe", updatedBy = admin.id)
        // A corrupt row must not hide points — failing open preserves today's behaviour, and a suppression
        // flag that engages by accident is worse than one that does not engage.
        service.getHideRankingPoints().hidden shouldBe false
    }

    @Test
    fun `an admin ticks the flag and the read reflects it, with an audit entry (#865)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val set = service.setHideRankingPoints(token = token(uid = "admin"), hidden = true).shouldBeRight()

        set.hidden shouldBe true
        service.getHideRankingPoints().hidden shouldBe true
        AuditRepository()
            .list(actions = listOf(element = AuditAction.SETTINGS_HIDE_RANKING_POINTS_CHANGED), limit = 10, offset = 0)
            .second shouldBe 1L
    }

    @Test
    fun `a non-admin cannot tick the flag (#865)`() {
        // A points manager may SEE points while it is on, but only an administrator decides whether it is.
        provision(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))
        service.setHideRankingPoints(token = token(uid = "pm"), hidden = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `with the flag on, only the five privileged roles see points (#865)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setHideRankingPoints(token = token(uid = "admin"), hidden = true).shouldBeRight()

        // Exempt: match management + rater + points manager.
        listOf(Capability.HOST, Capability.CLUB_OWNER, Capability.RATER, Capability.POINTS_MANAGER, Capability.ADMINISTRATOR)
            .forEach { role ->
                val viewer = provision(uid = "sees-$role", roles = setOf(Capability.PLAYER, role))
                withClue(clue = "$role should still see points") {
                    service.pointsVisibleTo(viewer = viewer) shouldBe true
                }
            }

        // Suppressed: a plain player, a researcher, and an anonymous visitor.
        listOf(Capability.PLAYER, Capability.RESEARCHER).forEach { role ->
            val viewer = provision(uid = "hidden-$role", roles = setOf(element = role))
            withClue(clue = "$role should NOT see points") {
                service.pointsVisibleTo(viewer = viewer) shouldBe false
            }
        }
        service.pointsVisibleTo(viewer = null) shouldBe false
        admin.id shouldNotBe null
    }

    @Test
    fun `there is no owner-self exemption - the rule is capability-only (#865)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setHideRankingPoints(token = token(uid = "admin"), hidden = true).shouldBeRight()
        val player = provision(uid = "player")

        // Deliberately unlike #186, where the owner DOES see their own precise rating. Pinned so nobody
        // "restores consistency" by adding a carve-out: pointsVisibleTo takes no subject, only a viewer.
        service.pointsVisibleTo(viewer = player) shouldBe false
        admin.id shouldNotBe player.id
    }

    @Test
    fun `un-ticking the flag restores visibility and says so in the audit (#865)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setHideRankingPoints(token = token(uid = "admin"), hidden = true).shouldBeRight()

        // The recovery path, and the one that matters most: an admin who hid points must be able to
        // un-hide them, and the audit trail has to record which direction each change went rather than
        // just that "the flag changed".
        val restored = service.setHideRankingPoints(token = token(uid = "admin"), hidden = false).shouldBeRight()

        restored.hidden shouldBe false
        service.pointsVisibleTo(viewer = null) shouldBe true
        val entries =
            AuditRepository()
                .list(actions = listOf(element = AuditAction.SETTINGS_HIDE_RANKING_POINTS_CHANGED), limit = 10, offset = 0)
        entries.second shouldBe 2L
        entries.first.map { it.summary } shouldContainExactlyInAnyOrder
            listOf(
                "Hid ranking points from players and researchers",
                "Showed ranking points to players and researchers",
            )
    }

    @Test
    fun `the route-facing response carries the flag and its provenance (#865)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        // Unset: no provenance, and not hidden.
        service.getHideRankingPointsResponse().hidden shouldBe false
        service.getHideRankingPointsResponse().updatedBy.shouldBeNull()

        service.setHideRankingPoints(token = token(uid = "admin"), hidden = true).shouldBeRight()

        val response = service.getHideRankingPointsResponse()
        response.hidden shouldBe true
        response.updatedBy.shouldNotBeNull()
        response.updatedAt.shouldNotBeNull()
    }
}
