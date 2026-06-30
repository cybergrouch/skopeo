// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.UserIdentity
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class NameRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val names = NameRepository()

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
                    names = emptyList(),
                ),
        ).id

    @Test
    fun `create stores an active name`() {
        val userId = newUser(uid = "u1")

        val name = names.create(userId = userId, type = NameType.NICKNAME, value = "Johnny")

        name.type shouldBe NameType.NICKNAME
        name.isActive.shouldBeTrue()
        names.listByUser(userId = userId).single().id shouldBe name.id
        names.findById(id = name.id).shouldBeRight()
    }

    @Test
    fun `findById returns NotFound when absent`() {
        names.findById(id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `adding a DISPLAY name disables the previous active display`() {
        val userId = newUser(uid = "u2")
        val first = names.create(userId = userId, type = NameType.DISPLAY, value = "Juan")

        val second = names.create(userId = userId, type = NameType.DISPLAY, value = "Johnny")

        names.findById(id = first.id).shouldBeRight().isActive.shouldBeFalse()
        second.isActive.shouldBeTrue()
        names.listByUser(userId = userId).count { it.type == NameType.DISPLAY && it.isActive } shouldBe 1
    }

    @Test
    fun `adding a FIRST or LAST name supersedes the previous active one (#196)`() {
        val userId = newUser(uid = "u2b")
        val firstA = names.create(userId = userId, type = NameType.FIRST, value = "Juan")
        val lastA = names.create(userId = userId, type = NameType.LAST, value = "Cruz")

        val firstB = names.create(userId = userId, type = NameType.FIRST, value = "Juancho")
        val lastB = names.create(userId = userId, type = NameType.LAST, value = "dela Cruz")

        // The prior active first/last are disabled; exactly one of each stays active.
        names.findById(id = firstA.id).shouldBeRight().isActive.shouldBeFalse()
        names.findById(id = lastA.id).shouldBeRight().isActive.shouldBeFalse()
        firstB.isActive.shouldBeTrue()
        lastB.isActive.shouldBeTrue()
        names.listByUser(userId = userId).count { it.type == NameType.FIRST && it.isActive } shouldBe 1
        names.listByUser(userId = userId).count { it.type == NameType.LAST && it.isActive } shouldBe 1
    }

    @Test
    fun `multiple names of the same non-display type are allowed`() {
        val userId = newUser(uid = "u3")
        names.create(userId = userId, type = NameType.NICKNAME, value = "JB")
        names.create(userId = userId, type = NameType.NICKNAME, value = "Boy")

        names.listByUser(userId = userId).size shouldBe 2
    }

    @Test
    fun `setActive disables then re-enables a name`() {
        val userId = newUser(uid = "u4")
        val name = names.create(userId = userId, type = NameType.NICKNAME, value = "JB")

        val disabled = names.setActive(id = name.id, active = false, disabledAt = LocalDateTime.now()).shouldBeRight()
        disabled.isActive.shouldBeFalse()
        disabled.disabledAt.shouldNotBeNull()

        names.setActive(id = name.id, active = true, disabledAt = null).shouldBeRight().isActive.shouldBeTrue()
    }

    @Test
    fun `setActive reports absence`() {
        names.setActive(id = UUID.randomUUID(), active = false, disabledAt = LocalDateTime.now())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `re-enabling a former display name collides with the current one`() {
        val userId = newUser(uid = "u5")
        val first = names.create(userId = userId, type = NameType.DISPLAY, value = "Juan")
        names.create(userId = userId, type = NameType.DISPLAY, value = "Johnny") // disables `first`

        names.setActive(id = first.id, active = true, disabledAt = null).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
    }
}
