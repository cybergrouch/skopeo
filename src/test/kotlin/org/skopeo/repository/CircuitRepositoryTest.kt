// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateCircuitCommand
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class CircuitRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val circuits = CircuitRepository()

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
                ),
        ).id

    @Test
    fun `create round-trips and findById is null for a missing circuit`() {
        val admin = newUser(uid = "admin")
        val circuit = circuits.create(command = CreateCircuitCommand(name = "NORTH", createdBy = admin))

        circuits.findById(id = circuit.id)!!.let {
            it.name shouldBe "NORTH"
            it.createdBy shouldBe admin
            it.isActive shouldBe true
        }
        circuits.findById(id = UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `list returns active circuits alphabetically and hides disabled ones`() {
        val admin = newUser(uid = "admin")
        circuits.create(command = CreateCircuitCommand(name = "SOUTH", createdBy = admin))
        val north = circuits.create(command = CreateCircuitCommand(name = "NORTH", createdBy = admin))

        circuits.list().map { it.name } shouldBe listOf("NORTH", "SOUTH")

        circuits.disable(id = north.id) shouldBe true
        circuits.list().map { it.name } shouldBe listOf("SOUTH")
    }

    @Test
    fun `rename updates the name and is null for a missing circuit`() {
        val admin = newUser(uid = "admin")
        val circuit = circuits.create(command = CreateCircuitCommand(name = "NORTH", createdBy = admin))

        circuits.rename(id = circuit.id, name = "NORTH REGION")!!.name shouldBe "NORTH REGION"
        circuits.rename(id = UUID.randomUUID(), name = "X").shouldBeNull()
    }

    @Test
    fun `disable is idempotent and false for a missing or already-disabled circuit`() {
        val admin = newUser(uid = "admin")
        val circuit = circuits.create(command = CreateCircuitCommand(name = "NORTH", createdBy = admin))

        circuits.disable(id = circuit.id) shouldBe true
        circuits.disable(id = circuit.id) shouldBe false
        circuits.disable(id = UUID.randomUUID()) shouldBe false
        circuits.list() shouldHaveSize 0
    }
}
