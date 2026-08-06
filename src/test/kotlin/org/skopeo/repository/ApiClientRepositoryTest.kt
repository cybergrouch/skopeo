// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.client.toDomain
import org.skopeo.domain.model.ApiClientStatus
import org.skopeo.domain.model.ApiKeyStatus
import org.skopeo.domain.model.InsertApiKeyCommand
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class ApiClientRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val repo = ApiClientRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun insertKey(
        clientId: UUID,
        hash: String,
        scopes: Set<Capability> = emptySet(),
        expiresAt: LocalDateTime? = null,
    ) = repo.insertKey(
        command =
            InsertApiKeyCommand(
                clientId = clientId,
                keyPrefix = "skopeo_live_ABCDEF",
                keyHash = hash,
                scopes = scopes,
                createdBy = null,
                expiresAt = expiresAt,
            ),
    )

    @Test
    fun `creates an active client with no keys`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        client.name shouldBe "Partner A"
        client.status shouldBe ApiClientStatus.ACTIVE
        client.keys shouldHaveSize 0
    }

    @Test
    fun `issues keys under a client, round-tripping scopes, and supports two active keys`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        insertKey(clientId = client.id, hash = "hash-1", scopes = setOf(Capability.PLAYER, Capability.HOST))
        insertKey(clientId = client.id, hash = "hash-2")

        val loaded = repo.findClientById(id = client.id).shouldNotBeNull().toDomain()
        loaded.keys shouldHaveSize 2
        loaded.keys.map { it.status }.toSet() shouldBe setOf(element = ApiKeyStatus.ACTIVE)
        val scoped = loaded.keys.first { it.scopes.isNotEmpty() }
        scoped.scopes shouldBe setOf(Capability.PLAYER, Capability.HOST)
    }

    @Test
    fun `finds a key by hash with its client status, and returns null for an unknown hash`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        insertKey(clientId = client.id, hash = "hash-1")

        val (keyEntity, clientStatusRaw) = repo.findKeyByHash(hash = "hash-1").shouldNotBeNull()
        keyEntity.clientId shouldBe client.id
        clientStatusRaw shouldBe ApiClientStatus.ACTIVE.name

        repo.findKeyByHash(hash = "missing").shouldBeNull()
    }

    @Test
    fun `revokes an active key of the owning client, and refuses otherwise`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        val other = repo.createClient(name = "Partner B", createdBy = null).toDomain()
        val key = insertKey(clientId = client.id, hash = "hash-1")
        val now = LocalDateTime.now()

        // Wrong client cannot revoke it.
        repo.revokeKey(clientId = other.id, keyId = key.id, revokedAt = now) shouldBe false
        // The owner revokes it once…
        repo.revokeKey(clientId = client.id, keyId = key.id, revokedAt = now) shouldBe true
        // …and a second revoke is a no-op (already revoked).
        repo.revokeKey(clientId = client.id, keyId = key.id, revokedAt = now) shouldBe false

        val (keyEntity, _) = repo.findKeyByHash(hash = "hash-1").shouldNotBeNull()
        keyEntity.status shouldBe ApiKeyStatus.REVOKED.name
        keyEntity.revokedAt.shouldNotBeNull()
    }

    @Test
    fun `records last-used time`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        val key = insertKey(clientId = client.id, hash = "hash-1")
        repo.findKeyByHash(hash = "hash-1")!!.first.lastUsedAt.shouldBeNull()

        repo.touchLastUsed(keyId = key.id, usedAt = LocalDateTime.now())
        repo.findKeyByHash(hash = "hash-1")!!.first.lastUsedAt.shouldNotBeNull()
    }

    @Test
    fun `sets and clears a client's rate-limit override`() {
        val client = repo.createClient(name = "Partner A", createdBy = null).toDomain()
        client.rateLimitPerMin.shouldBeNull()

        repo.setRateLimit(clientId = client.id, rateLimitPerMin = 250).shouldNotBeNull().toDomain().rateLimitPerMin shouldBe 250
        repo.findClientById(id = client.id).shouldNotBeNull().toDomain().rateLimitPerMin shouldBe 250

        repo.setRateLimit(clientId = client.id, rateLimitPerMin = null).shouldNotBeNull().toDomain().rateLimitPerMin.shouldBeNull()

        // A missing client returns null.
        repo.setRateLimit(clientId = UUID.randomUUID(), rateLimitPerMin = 10).shouldBeNull()
    }

    @Test
    fun `lists clients newest first`() {
        val first = repo.createClient(name = "First", createdBy = null).toDomain()
        val second = repo.createClient(name = "Second", createdBy = null).toDomain()
        repo.listClients().map { it.toDomain().id } shouldContainExactly listOf(second.id, first.id)
    }
}
