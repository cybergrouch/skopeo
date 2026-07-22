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
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.ThemeSetting
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.AppSettingsRepository
import org.skopeo.repository.AppSettingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime

class ThemeServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val settings = AppSettingsRepository()
    private val service = ThemeService(settings = settings, users = users)

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
    fun `the default theme is AUTO (#378)`() {
        service.getTheme().theme shouldBe ThemeSetting.AUTO
    }

    @Test
    fun `an admin sets a valid theme and the read reflects it (#378)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val set = service.setTheme(token = token(uid = "admin"), theme = "GRASS").shouldBeRight()
        set.theme shouldBe ThemeSetting.GRASS

        service.getTheme().theme shouldBe ThemeSetting.GRASS
    }

    @Test
    fun `setTheme accepts each new seasonal theme value`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val newThemes =
            listOf(
                ThemeSetting.VALENTINES,
                ThemeSetting.SPRING,
                ThemeSetting.RAINY,
                ThemeSetting.HALLOWEEN,
                ThemeSetting.AUTUMN,
                ThemeSetting.SKOPEO_OG,
            )
        newThemes.forEach { theme ->
            service.setTheme(token = token(uid = "admin"), theme = theme.name).shouldBeRight().theme shouldBe theme
            service.getTheme().theme shouldBe theme
        }
    }

    @Test
    fun `theme parsing is case-insensitive (#378)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setTheme(token = token(uid = "admin"), theme = "clay").shouldBeRight().theme shouldBe ThemeSetting.CLAY
    }

    @Test
    fun `a non-admin cannot set the theme (#378)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.setTheme(token = token(uid = "host"), theme = "GRASS")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unprovisioned caller cannot set the theme (#378)`() {
        service.setTheme(token = token(uid = "ghost"), theme = "GRASS")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unknown theme string is a validation error (#378)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.setTheme(token = token(uid = "admin"), theme = "NEON")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `getTheme falls back to AUTO when the stored value is unrecognized (#378)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // A value that no longer maps to a known theme (e.g. a renamed/removed theme) reads back as AUTO.
        settings.upsert(key = "ui_theme", value = "RETIRED_THEME", updatedBy = admin.id)
        service.getTheme().theme shouldBe ThemeSetting.AUTO
    }

    @Test
    fun `getTheme falls back to AUTO when no setting row exists (#378)`() {
        transaction { AppSettingsTable.deleteAll() }
        val current = service.getTheme()
        current.theme shouldBe ThemeSetting.AUTO
        current.updatedBy.shouldBeNull()
    }

    @Test
    fun `local theme defaults to unset for a fresh user (#514)`() {
        provision(uid = "u")
        val value = service.getLocalTheme(token = token(uid = "u")).shouldBeRight()
        value.theme.shouldBeNull()
        value.setAt.shouldBeNull()
    }

    @Test
    fun `setting a local theme stamps setAt and the read reflects it (#514)`() {
        provision(uid = "u")
        val at = LocalDateTime.of(2026, 1, 15, 10, 0)

        val set = service.setLocalTheme(token = token(uid = "u"), theme = "GRASS", now = at).shouldBeRight()
        set.theme shouldBe ThemeSetting.GRASS
        set.setAt shouldBe at

        val read = service.getLocalTheme(token = token(uid = "u")).shouldBeRight()
        read.theme shouldBe ThemeSetting.GRASS
        read.setAt shouldBe at
    }

    @Test
    fun `setting a null local theme clears both columns (#514)`() {
        provision(uid = "u")
        service.setLocalTheme(token = token(uid = "u"), theme = "CLAY", now = LocalDateTime.of(2026, 1, 15, 10, 0)).shouldBeRight()

        val cleared = service.setLocalTheme(token = token(uid = "u"), theme = null).shouldBeRight()
        cleared.theme.shouldBeNull()
        cleared.setAt.shouldBeNull()

        val read = service.getLocalTheme(token = token(uid = "u")).shouldBeRight()
        read.theme.shouldBeNull()
        read.setAt.shouldBeNull()
    }

    @Test
    fun `local theme parsing is case-insensitive (#514)`() {
        provision(uid = "u")
        service.setLocalTheme(token = token(uid = "u"), theme = "clay").shouldBeRight().theme shouldBe ThemeSetting.CLAY
    }

    @Test
    fun `an unknown local theme string is a validation error (#514)`() {
        provision(uid = "u")
        service.setLocalTheme(token = token(uid = "u"), theme = "NEON")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an unprovisioned caller cannot read a local theme (#514)`() {
        service.getLocalTheme(token = token(uid = "ghost"))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unprovisioned caller cannot set a local theme (#514)`() {
        service.setLocalTheme(token = token(uid = "ghost"), theme = "GRASS")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `re-setting a local theme re-stamps setAt so the user reclaims their choice (#514)`() {
        provision(uid = "u")
        val first = LocalDateTime.of(2026, 1, 1, 0, 0)
        val later = LocalDateTime.of(2026, 4, 5, 12, 0)
        service.setLocalTheme(token = token(uid = "u"), theme = "GRASS", now = first).shouldBeRight()

        val second = service.setLocalTheme(token = token(uid = "u"), theme = "CLAY", now = later).shouldBeRight()
        second.theme shouldBe ThemeSetting.CLAY
        second.setAt shouldBe later
    }

    @Test
    fun `a local theme set to one user does not leak to another (#514)`() {
        provision(uid = "a")
        provision(uid = "b")
        service.setLocalTheme(token = token(uid = "a"), theme = "GRASS", now = LocalDateTime.of(2026, 1, 1, 0, 0)).shouldBeRight()

        service.getLocalTheme(token = token(uid = "b")).shouldBeRight().theme.shouldBeNull()
    }
}
