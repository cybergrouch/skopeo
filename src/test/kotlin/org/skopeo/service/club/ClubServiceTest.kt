// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.club

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class ClubServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clubs = ClubRepository()
    private val service = ClubService(clubs = clubs, users = users)

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
    fun `an admin creates a club and it appears in the list`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val created = service.create(token = token(uid = "admin"), name = "  Downtown TC  ").shouldBeRight()
        created.name shouldBe "Downtown TC" // trimmed
        created.owners shouldHaveSize 0

        val list = service.list(token = token(uid = "admin")).shouldBeRight()
        list shouldHaveSize 1
        list.single().name shouldBe "Downtown TC"
    }

    @Test
    fun `an admin assigns and removes owners, resolving display names`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner1 = provision(uid = "owner1", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val owner2 = provision(uid = "owner2", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val club = service.create(token = token(uid = "admin"), name = "West End").shouldBeRight()

        val withOne = service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight()
        withOne.owners shouldHaveSize 1
        withOne.owners.single().let {
            it.userId shouldBe owner1.id
            it.displayName shouldBe "owner1"
            it.publicCode shouldBe owner1.publicCode
        }

        // Re-assigning the same owner is idempotent.
        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight().owners shouldHaveSize 1

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner2.id).shouldBeRight().owners shouldHaveSize 2

        val afterRemove = service.removeOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight()
        afterRemove.owners shouldHaveSize 1
        afterRemove.owners.single().userId shouldBe owner2.id
    }

    @Test
    fun `creating and owner management is administrator-only`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), name = "X").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `listing is readable by staff but not plain players (#313)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "player")
        service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        // A HOST (event creator) may read the list to pick a club…
        service.list(token = token(uid = "host")).shouldBeRight() shouldHaveSize 1
        // …but a plain player and an unprovisioned caller cannot.
        service.list(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `create rejects a blank name`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.create(token = token(uid = "admin"), name = "   ").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `assignOwner is not-found for a missing club and validation for an unknown user`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = UUID.randomUUID(), userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `removeOwner is a not-found for a missing club`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        service.removeOwner(token = token(uid = "admin"), clubId = UUID.randomUUID(), userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `assignOwner rejects an inactive user`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        users.deactivate(id = owner.id).shouldBeRight()
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `assignOwner rejects a user without the CLUB_OWNER capability (#317)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val plain = provision(uid = "plain", roles = setOf(element = Capability.PLAYER))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = plain.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an owner without a display name is shown by public code`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "nameless",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "nameless", isPrimary = true),
                        names = emptyList(),
                        capabilities = setOf(element = Capability.CLUB_OWNER),
                    ),
            )
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner.id)
            .shouldBeRight()
            .owners
            .single()
            .let {
                it.displayName.shouldBeNull()
                it.publicCode shouldBe owner.publicCode
            }
    }
}
