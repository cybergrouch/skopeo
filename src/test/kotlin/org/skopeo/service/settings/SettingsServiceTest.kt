// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.settings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.SnapshotSource
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.AppSettingsTable
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
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
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

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
        set.source shouldBe SnapshotSource.POINTS

        service.getStandingsSource().source shouldBe SnapshotSource.POINTS
    }

    @Test
    fun `setting the standings source is case-insensitive and records an audit row (#146)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setStandingsSource(token = token(uid = "admin"), source = "points").shouldBeRight().source shouldBe
            SnapshotSource.POINTS

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
}
