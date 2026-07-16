// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class AppSettingsRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val settings = AppSettingsRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = emptySet(),
                ),
        ).id

    @Test
    fun `get returns null for a missing key (#378)`() {
        settings.get(key = "absent").shouldBeNull()
    }

    @Test
    fun `upsert inserts then get round-trips the value and actor (#378)`() {
        val admin = newUser(uid = "admin")
        val written = settings.upsert(key = "ui_theme", value = "GRASS", updatedBy = admin)
        written.value shouldBe "GRASS"
        written.updatedBy shouldBe admin
        written.updatedAt.shouldNotBeNull()

        val read = settings.get(key = "ui_theme").shouldNotBeNull()
        read.value shouldBe "GRASS"
        read.updatedBy shouldBe admin
    }

    @Test
    fun `upsert inserts a brand-new key not present after reset (#378)`() {
        val admin = newUser(uid = "admin")
        // ui_theme is seeded by reset, so a different key exercises the insert (not update) branch.
        val written = settings.upsert(key = "feature_banner", value = "on", updatedBy = admin)
        written.value shouldBe "on"
        written.updatedBy shouldBe admin

        settings.get(key = "feature_banner").shouldNotBeNull().value shouldBe "on"
    }

    @Test
    fun `upsert overwrites an existing key in place (#378)`() {
        val admin1 = newUser(uid = "admin1")
        val admin2 = newUser(uid = "admin2")
        settings.upsert(key = "ui_theme", value = "GRASS", updatedBy = admin1)

        val updated = settings.upsert(key = "ui_theme", value = "CLAY", updatedBy = admin2)
        updated.value shouldBe "CLAY"
        updated.updatedBy shouldBe admin2

        settings.get(key = "ui_theme").shouldNotBeNull().value shouldBe "CLAY"
    }
}
