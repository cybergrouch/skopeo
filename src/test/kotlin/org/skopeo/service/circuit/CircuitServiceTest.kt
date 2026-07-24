// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.circuit

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
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase

class CircuitServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val circuits = CircuitRepository()
    private val service = CircuitService(circuits = circuits, users = users)

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
    fun `an admin creates a circuit and it appears in the list`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val created = service.create(token = token(uid = "admin"), name = "  NORTH  ").shouldBeRight()
        created.name shouldBe "NORTH"

        val list = service.list(token = token(uid = "admin")).shouldBeRight()
        list shouldHaveSize 1
        list.single().name shouldBe "NORTH"
    }

    @Test
    fun `a blank name is rejected`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service
            .create(token = token(uid = "admin"), name = "   ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `creating is administrator-only but listing is staff-visible`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), name = "X").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // A HOST may still read the list (to pick a circuit for a tournament).
        service.list(token = token(uid = "host")).shouldBeRight()
    }

    @Test
    fun `a plain player cannot read the list`() {
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))

        service.list(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `rename and delete work for an admin and delete hides the circuit`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val created = service.create(token = token(uid = "admin"), name = "NORTH").shouldBeRight()

        service.rename(token = token(uid = "admin"), circuitId = created.id, name = "NORTH REGION").shouldBeRight().name shouldBe
            "NORTH REGION"
        service.delete(token = token(uid = "admin"), circuitId = created.id).shouldBeRight()
        service.list(token = token(uid = "admin")).shouldBeRight() shouldHaveSize 0
    }

    @Test
    fun `deleting a missing circuit is a not-found`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service
            .delete(token = token(uid = "admin"), circuitId = java.util.UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `renaming a missing circuit is a not-found and a blank rename is rejected`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val created = service.create(token = token(uid = "admin"), name = "NORTH").shouldBeRight()

        service
            .rename(token = token(uid = "admin"), circuitId = java.util.UUID.randomUUID(), name = "X")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .rename(token = token(uid = "admin"), circuitId = created.id, name = "   ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an unknown token is forbidden for both admin and staff actions`() {
        // No user is provisioned for this uid, so the caller resolves to null in both guards.
        service.create(token = token(uid = "ghost"), name = "NORTH").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
