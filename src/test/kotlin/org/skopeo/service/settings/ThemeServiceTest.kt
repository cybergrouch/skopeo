// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.settings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase

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
}
