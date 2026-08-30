// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.client

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.common.security.ClientAuthResult
import org.skopeo.common.security.ClientPrincipal
import org.skopeo.domain.mapper.entity.client.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.ApiClientStatus
import org.skopeo.domain.model.ApiKeyEnvironment
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.InsertApiKeyCommand
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.ApiClientRepository
import org.skopeo.repository.ApiClientsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDateTime
import java.util.UUID

class ApiClientServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clients = ApiClientRepository()
    private val service = ApiClientService(clients = clients, users = users)

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
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun admin(uid: String = "admin"): VerifiedFirebaseToken {
        provision(uid = uid, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        return token(uid = uid)
    }

    @Test
    fun `an admin creates a client, a non-admin cannot, and a blank name is rejected`() {
        service.createClient(token = admin(), name = "Partner A").shouldBeRight().name shouldBe "Partner A"

        provision(uid = "plain", roles = setOf(element = Capability.PLAYER))
        service.createClient(token = token(uid = "plain"), name = "X").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()

        service.createClient(token = admin(uid = "admin2"), name = "   ").shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // An over-long name is rejected.
        service.createClient(token = admin(uid = "admin3"), name = "a".repeat(n = 121)).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // A token for a user that was never provisioned resolves to no caller → Forbidden.
        service.createClient(token = token(uid = "ghost"), name = "X").shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `lists clients for an admin only`() {
        val adminToken = admin()
        service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        service.listClients(token = adminToken).shouldBeRight().map { it.name } shouldBe listOf(element = "Partner A")

        provision(uid = "plain", roles = setOf(element = Capability.PLAYER))
        service.listClients(token = token(uid = "plain")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `issues a key returning the plaintext once, and rejects an unknown client or bad expiry`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()

        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = setOf(element = Capability.PLAYER.name),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = null,
            ).shouldBeRight()
        ApiKeyCrypto.looksValid(raw = issued.apiKey) shouldBe true
        issued.key.scopes shouldBe listOf(element = Capability.PLAYER.name)

        service.issueKey(
            token = adminToken,
            clientId = UUID.randomUUID(),
            scopeNames = emptySet(),
            environmentRaw = ApiKeyEnvironment.LIVE.name,
            expiresInDays = null,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        service.issueKey(
            token = adminToken,
            clientId = UUID.fromString(client.id),
            scopeNames = emptySet(),
            environmentRaw = ApiKeyEnvironment.LIVE.name,
            expiresInDays = 0,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `issueKey rejects an unknown scope or environment (#225)`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()

        service.issueKey(
            token = adminToken,
            clientId = UUID.fromString(client.id),
            scopeNames = setOf(element = "NOT_A_SCOPE"),
            environmentRaw = ApiKeyEnvironment.LIVE.name,
            expiresInDays = null,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        service.issueKey(
            token = adminToken,
            clientId = UUID.fromString(client.id),
            scopeNames = emptySet(),
            environmentRaw = "NOT_AN_ENV",
            expiresInDays = null,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `revokes a key, and a missing key is NotFound`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = emptySet(),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = null,
            ).shouldBeRight()

        service.revokeKey(token = adminToken, clientId = UUID.fromString(client.id), keyId = UUID.fromString(issued.key.id)).shouldBeRight()
        service.revokeKey(token = adminToken, clientId = UUID.fromString(client.id), keyId = UUID.fromString(issued.key.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `authenticate resolves a valid key to its client and scopes`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = setOf(Capability.PLAYER.name, Capability.HOST.name),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = 30,
            ).shouldBeRight()

        val result = service.authenticate(rawKey = issued.apiKey).shouldBeInstanceOf<ClientAuthResult.Authenticated>()
        result.principal.clientId shouldBe UUID.fromString(client.id)
        result.principal.keyId shouldBe UUID.fromString(issued.key.id)
        result.principal.scopes shouldBe setOf(Capability.PLAYER, Capability.HOST)
    }

    @Test
    fun `authenticate classifies missing, malformed, and unknown keys`() {
        service.authenticate(rawKey = "") shouldBe ClientAuthResult.Missing
        service.authenticate(rawKey = "   ") shouldBe ClientAuthResult.Missing
        service.authenticate(rawKey = "not-a-key") shouldBe ClientAuthResult.Invalid
        // Well-formed but never issued → unknown → Invalid.
        val orphan = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        service.authenticate(rawKey = orphan.plaintext) shouldBe ClientAuthResult.Invalid
    }

    @Test
    fun `authenticate rejects a revoked key`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = emptySet(),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = null,
            ).shouldBeRight()
        service.revokeKey(token = adminToken, clientId = UUID.fromString(client.id), keyId = UUID.fromString(issued.key.id)).shouldBeRight()

        service.authenticate(rawKey = issued.apiKey) shouldBe ClientAuthResult.Forbidden
    }

    @Test
    fun `playerDirectory returns the public projection of players for a RESEARCHER-scoped key`() {
        val a = provision(uid = "alice", roles = setOf(element = Capability.PLAYER))
        val b = provision(uid = "bob", roles = setOf(element = Capability.PLAYER))
        val researcher =
            ClientPrincipal(clientId = UUID.randomUUID(), keyId = UUID.randomUUID(), scopes = setOf(element = Capability.RESEARCHER))
        service.playerDirectory(principal = researcher).shouldBeRight().map { it.publicCode } shouldContainExactlyInAnyOrder
            listOf(a.publicCode, b.publicCode)
    }

    @Test
    fun `playerDirectory is Forbidden for a key without the RESEARCHER scope (#597)`() {
        val unscoped =
            ClientPrincipal(clientId = UUID.randomUUID(), keyId = UUID.randomUUID(), scopes = setOf(element = Capability.PLAYER))
        service.playerDirectory(principal = unscoped).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `effectiveCapabilities returns the intersection of key scopes and the user's capabilities`() {
        provision(uid = "u1", roles = setOf(Capability.PLAYER, Capability.RESEARCHER))
        val principal =
            ClientPrincipal(
                clientId = UUID.randomUUID(),
                keyId = UUID.randomUUID(),
                scopes = setOf(Capability.RESEARCHER, Capability.HOST),
            )
        val effective = service.effectiveCapabilities(token = token(uid = "u1"), principal = principal).shouldBeRight()
        // Only RESEARCHER is in both the key's scopes and the user's capabilities.
        effective.capabilities shouldBe listOf(element = Capability.RESEARCHER.name)
    }

    @Test
    fun `effectiveCapabilities is Forbidden when the token resolves to no user`() {
        val principal =
            ClientPrincipal(clientId = UUID.randomUUID(), keyId = UUID.randomUUID(), scopes = setOf(element = Capability.PLAYER))
        service.effectiveCapabilities(token = token(uid = "ghost"), principal = principal)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `resolveClientId returns the client for a valid key and null otherwise`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = emptySet(),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = null,
            ).shouldBeRight()

        service.resolveClientId(rawKey = issued.apiKey) shouldBe UUID.fromString(client.id)
        service.resolveClientId(rawKey = "") shouldBe null
        service.resolveClientId(rawKey = "garbage") shouldBe null
        service.resolveClientId(rawKey = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE).plaintext) shouldBe null
    }

    @Test
    fun `setRateLimit sets and clears a client's override`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        service.setRateLimit(token = adminToken, clientId = UUID.fromString(client.id), rateLimitPerMin = 300)
            .shouldBeRight().rateLimitPerMin shouldBe 300
        service.setRateLimit(token = adminToken, clientId = UUID.fromString(client.id), rateLimitPerMin = null)
            .shouldBeRight().rateLimitPerMin shouldBe null
    }

    @Test
    fun `setRateLimit rejects a non-admin, a non-positive limit, and a missing client`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        provision(uid = "plain", roles = setOf(element = Capability.PLAYER))
        service.setRateLimit(token = token(uid = "plain"), clientId = UUID.fromString(client.id), rateLimitPerMin = 100)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.setRateLimit(token = adminToken, clientId = UUID.fromString(client.id), rateLimitPerMin = 0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setRateLimit(token = adminToken, clientId = UUID.randomUUID(), rateLimitPerMin = 100)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `rateLimitForKey returns the client override, else the default`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        service.rateLimitForKey(key = client.id.toString(), default = 120) shouldBe 120
        service.setRateLimit(token = adminToken, clientId = UUID.fromString(client.id), rateLimitPerMin = 5).shouldBeRight()
        service.rateLimitForKey(key = client.id.toString(), default = 120) shouldBe 5
        // Anonymous per-host buckets and unknown client ids fall back to the default.
        service.rateLimitForKey(key = "anon:127.0.0.1", default = 120) shouldBe 120
        service.rateLimitForKey(key = UUID.randomUUID().toString(), default = 120) shouldBe 120
    }

    @Test
    fun `authenticate rejects an expired key`() {
        val client = clients.createClient(name = "Partner A", createdBy = null).toDomain()
        val generated = ApiKeyCrypto.generate(environment = ApiKeyEnvironment.LIVE)
        clients.insertKey(
            command =
                InsertApiKeyCommand(
                    clientId = client.id,
                    keyPrefix = generated.displayPrefix,
                    keyHash = generated.hash,
                    scopes = emptySet(),
                    createdBy = null,
                    expiresAt = LocalDateTime.now().minusDays(1),
                ),
        )
        service.authenticate(rawKey = generated.plaintext) shouldBe ClientAuthResult.Forbidden
    }

    @Test
    fun `authenticate rejects a key whose client is suspended`() {
        val adminToken = admin()
        val client = service.createClient(token = adminToken, name = "Partner A").shouldBeRight()
        val issued =
            service.issueKey(
                token = adminToken,
                clientId = UUID.fromString(client.id),
                scopeNames = emptySet(),
                environmentRaw = ApiKeyEnvironment.LIVE.name,
                expiresInDays = null,
            ).shouldBeRight()
        transaction {
            ApiClientsTable.update(where = { ApiClientsTable.id eq UUID.fromString(client.id) }) {
                it[status] = ApiClientStatus.SUSPENDED.name
            }
        }
        service.authenticate(rawKey = issued.apiKey) shouldBe ClientAuthResult.Forbidden
    }
}
