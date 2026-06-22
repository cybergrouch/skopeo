package org.skopeo.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
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
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                names = emptyList(),
            ),
        ).id

    @Test
    fun `create stores an active name`() {
        val userId = newUser("u1")

        val name = names.create(userId = userId, type = NameType.NICKNAME, value = "Johnny", primary = false)

        name.type shouldBe NameType.NICKNAME
        name.isActive.shouldBeTrue()
        name.isPrimary.shouldBeFalse()
        names.listByUser(userId).single().id shouldBe name.id
        names.findById(name.id).shouldNotBeNull()
    }

    @Test
    fun `findById returns null when absent`() {
        names.findById(UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `creating a new primary demotes the previous one`() {
        val userId = newUser("u2")
        val first = names.create(userId = userId, type = NameType.FIRST, value = "Juan", primary = true)

        val second = names.create(userId = userId, type = NameType.PREFERRED, value = "Johnny", primary = true)

        names.findById(first.id)!!.isPrimary.shouldBeFalse()
        second.isPrimary.shouldBeTrue()
    }

    @Test
    fun `multiple names of the same type are allowed`() {
        val userId = newUser("u3")
        names.create(userId = userId, type = NameType.NICKNAME, value = "JB", primary = false)
        names.create(userId = userId, type = NameType.NICKNAME, value = "Boy", primary = false)

        names.listByUser(userId).size shouldBe 2
    }

    @Test
    fun `setActive disables then re-enables, and hasActiveName tracks it`() {
        val userId = newUser("u4")
        val name = names.create(userId = userId, type = NameType.FIRST, value = "Juan", primary = false)

        val disabled = names.setActive(id = name.id, active = false, disabledAt = LocalDateTime.now())
        disabled.shouldNotBeNull()
        disabled.isActive.shouldBeFalse()
        disabled.disabledAt.shouldNotBeNull()
        names.hasActiveName(userId).shouldBeFalse()

        names.setActive(id = name.id, active = true, disabledAt = null)
        names.hasActiveName(userId).shouldBeTrue()
    }

    @Test
    fun `setActive reports absence`() {
        names.setActive(id = UUID.randomUUID(), active = false, disabledAt = LocalDateTime.now()).shouldBeNull()
    }

    @Test
    fun `re-enabling a former primary collides with the current active primary`() {
        val userId = newUser("u5")
        val first = names.create(userId = userId, type = NameType.FIRST, value = "Juan", primary = true)
        names.setActive(id = first.id, active = false, disabledAt = LocalDateTime.now())
        names.create(userId = userId, type = NameType.PREFERRED, value = "Johnny", primary = true)

        // first is still flagged primary; re-enabling it would make two active primaries.
        shouldThrow<ExposedSQLException> {
            names.setActive(id = first.id, active = true, disabledAt = null)
        }
    }
}
