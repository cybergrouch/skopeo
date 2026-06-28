// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.seeding

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
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
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class PlayerListServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val service = PlayerListService()

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

    private fun host(uid: String = "host") = provision(uid = uid, roles = setOf(Capability.PLAYER, Capability.HOST))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    @Test
    fun `a host creates, lists, and reads back a named list`() {
        host(uid = "host")
        val created = service.create(token = token(uid = "host"), name = "Club Open 2026").shouldBeRight()
        created.name shouldBe "Club Open 2026"
        created.memberUserIds shouldHaveSize 0

        service.listMine(token = token(uid = "host")).shouldBeRight().single().id shouldBe created.id
        service.get(token = token(uid = "host"), listId = created.id).shouldBeRight().name shouldBe "Club Open 2026"
    }

    @Test
    fun `creating a list requires a non-blank name`() {
        host(uid = "host")
        service
            .create(token = token(uid = "host"), name = "   ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `managing lists is restricted to HOST, CLUB_OWNER, or ADMINISTRATOR`() {
        provision(uid = "player") // PLAYER only
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service.listMine(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.listMine(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // A CLUB_OWNER and an ADMINISTRATOR may.
        service.create(token = token(uid = "owner"), name = "Owner list").shouldBeRight()
        service.create(token = token(uid = "root"), name = "Admin list").shouldBeRight()
    }

    @Test
    fun `a list is private to its owner`() {
        host(uid = "host1")
        provision(uid = "host2", roles = setOf(Capability.PLAYER, Capability.HOST))
        val list = service.create(token = token(uid = "host1"), name = "Mine").shouldBeRight()

        service.get(token = token(uid = "host2"), listId = list.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.detail(token = token(uid = "host2"), listId = list.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.delete(token = token(uid = "host2"), listId = list.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `members can be added (PLAYER only), de-duplicated, resolved, and removed`() {
        host(uid = "host")
        val p1 = provision(uid = "p1")
        val list = service.create(token = token(uid = "host"), name = "Roster").shouldBeRight()

        service.addMember(token = token(uid = "host"), listId = list.id, userId = p1.id).shouldBeRight()
        val detail = service.detail(token = token(uid = "host"), listId = list.id).shouldBeRight()
        detail.members.single().id shouldBe p1.id.toString()

        // Adding the same player again conflicts; an unknown user is not found.
        service.addMember(token = token(uid = "host"), listId = list.id, userId = p1.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        service.addMember(token = token(uid = "host"), listId = list.id, userId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        // Removing works; removing a non-member is not found.
        service.removeMember(token = token(uid = "host"), listId = list.id, userId = p1.id).shouldBeRight()
        service.detail(token = token(uid = "host"), listId = list.id).shouldBeRight().members shouldHaveSize 0
        service.removeMember(token = token(uid = "host"), listId = list.id, userId = p1.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a non-PLAYER cannot be added to a list`() {
        host(uid = "host")
        val staffOnly = provision(uid = "staff", roles = setOf(element = Capability.HOST)) // no PLAYER
        val list = service.create(token = token(uid = "host"), name = "Roster").shouldBeRight()

        service.addMember(token = token(uid = "host"), listId = list.id, userId = staffOnly.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `deleting a list removes it`() {
        host(uid = "host")
        val list = service.create(token = token(uid = "host"), name = "Temp").shouldBeRight()

        service.delete(token = token(uid = "host"), listId = list.id).shouldBeRight()
        service.get(token = token(uid = "host"), listId = list.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
