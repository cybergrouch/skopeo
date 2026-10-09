// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.common.security.ClientPrincipal
import org.skopeo.domain.mapper.entity.client.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreatePlaceholderCommand
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.repository.ApiClientRepository
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.TeamUsersTable
import org.skopeo.repository.TeamsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventFor
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * The stale-account sweep (#1122): who the rule matches, what a dry run and a commit do, and who may run
 * it. The rule's exclusions are each a separate account in one fixture, so a regression names which one
 * broke rather than reporting a count.
 */
class StaleAccountServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 9, 12, 0)
    private val users = UserRepository()
    private val audit = AuditRepository()
    private val settings = SettingsService(users = users)
    private val service = StaleAccountService(users = users, settings = settings, clock = { now })

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    /** A self-sign-up created [daysAgo] days before [now]; returns its id. */
    private fun signUp(
        uid: String,
        daysAgo: Long,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): UUID {
        val id =
            users
                .provision(
                    command =
                        ProvisionUserCommand(
                            firebaseUid = uid,
                            identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                            names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                            capabilities = roles,
                        ),
                ).toDomain()
                .id
        createdDaysAgo(id = id, daysAgo = daysAgo)
        return id
    }

    // `created_at` is database-generated, so the test sets it with SQL rather than through the mapping.
    private fun createdDaysAgo(
        id: UUID,
        daysAgo: Long,
    ) = transaction {
        exec(stmt = "UPDATE users SET created_at = '${now.minusDays(daysAgo)}' WHERE id = '$id'")
    }

    private fun publicCode(id: UUID): String = users.findById(id = id).shouldBeRight().toDomain().publicCode

    private fun isActive(id: UUID): Boolean = users.findById(id = id).shouldBeRight().toDomain().isActive

    private fun admin(): UUID = signUp(uid = "root", daysAgo = 400, roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

    @Test
    fun `the rule matches only an old, unrated, untouched self-sign-up`() {
        val stale = signUp(uid = "stale", daysAgo = 45)
        val young = signUp(uid = "young", daysAgo = 10)
        val rated =
            signUp(
                uid = "rated",
                daysAgo = 45,
            ).also { RatingAssembler().setRating(userId = it, rating = BigDecimal("3.0"), level = "3.0") }
        val staff = signUp(uid = "staff", daysAgo = 45).also { CapabilityRepository().grant(userId = it, capability = Capability.HOST) }
        val inEvent = signUp(uid = "event", daysAgo = 45).also { fixtureEventFor(team1 = listOf(element = it), team2 = emptyList()) }
        // Match history without an event entry, so this proves the team_users exclusion on its own.
        val inMatch =
            signUp(uid = "match", daysAgo = 45).also { userId ->
                transaction {
                    val team =
                        TeamsTable.insertAndGetId {
                            it[name] = "T"
                            it[teamType] = "SINGLES"
                        }
                    TeamUsersTable.insert {
                        it[teamId] = team
                        it[TeamUsersTable.userId] = userId
                    }
                }
            }
        // The #1121 shape: an unrated account something was merged into.
        val mergeTarget =
            signUp(uid = "survivor", daysAgo = 45).also { survivor ->
                val retired = signUp(uid = "retired", daysAgo = 60)
                users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = false)
            }
        val placeholder =
            users
                .createPlaceholder(command = CreatePlaceholderCommand(displayName = "Placeholder", sex = "Male"))
                .toDomain()
                .id
                .also { createdDaysAgo(id = it, daysAgo = 45) }
        val deleted = signUp(uid = "deleted", daysAgo = 45).also { users.deactivate(id = it).shouldBeRight() }
        // A row with no sign-up time is never treated as old enough.
        val undated =
            signUp(uid = "undated", daysAgo = 45).also {
                transaction { exec(stmt = "UPDATE users SET created_at = NULL WHERE id = '$it'") }
            }

        admin()

        val swept = service.sweep(token = token(uid = "root"), dryRun = true).shouldBeRight().accounts.map { it.userId }

        mapOf(
            "too young" to young,
            "rated" to rated,
            "staff" to staff,
            "event-history" to inEvent,
            "match-history" to inMatch,
            "merge-target" to mergeTarget,
            "placeholder" to placeholder,
            "already-deleted" to deleted,
            "undated" to undated,
        ).forEach { (why, id) ->
            withClue(clue = "the $why account must not be swept") { swept.contains(element = id.toString()) shouldBe false }
        }
        swept shouldContainExactly listOf(element = stale.toString())
    }

    @Test
    fun `a dry run lists the stale accounts and changes nothing`() {
        admin()
        val older = signUp(uid = "older", daysAgo = 60)
        val newer = signUp(uid = "newer", daysAgo = 40)

        val preview = service.sweep(token = token(uid = "root"), dryRun = true).shouldBeRight()

        preview.dryRun shouldBe true
        preview.thresholdDays shouldBe 30
        preview.cutoff shouldBe now.minusDays(30).toString()
        // Oldest sign-up first.
        preview.accounts.map { it.publicCode } shouldContainExactly listOf(publicCode(id = older), publicCode(id = newer))
        isActive(id = older) shouldBe true
        isActive(id = newer) shouldBe true
        audit.list(actions = listOf(element = AuditAction.ACCOUNT_AUTO_DELETED), limit = 10, offset = 0).first.shouldBeEmpty()
        audit
            .list(actions = listOf(element = AuditAction.STALE_ACCOUNT_SWEEP_PREVIEWED), limit = 10, offset = 0)
            .first
            .single()
            .summary shouldContain "2 accounts would be deleted"
    }

    @Test
    fun `a commit soft-deletes each stale account, audits each one, and a re-run finds nothing new`() {
        val adminId = admin()
        val stale = signUp(uid = "stale", daysAgo = 45)
        val young = signUp(uid = "young", daysAgo = 5)

        val committed = service.sweep(token = token(uid = "root"), dryRun = false).shouldBeRight()

        committed.dryRun shouldBe false
        committed.accounts.map { it.userId } shouldContainExactly listOf(element = stale.toString())
        isActive(id = stale) shouldBe false
        isActive(id = young) shouldBe true
        val removal =
            audit.list(actions = listOf(element = AuditAction.ACCOUNT_AUTO_DELETED), limit = 10, offset = 0).first.single()
        removal.entityId shouldBe stale
        removal.actorUserId shouldBe adminId
        audit.list(actions = listOf(element = AuditAction.STALE_ACCOUNT_SWEEP_COMMITTED), limit = 10, offset = 0).first.size shouldBe 1

        // Swept accounts are no longer active, so the same run again has nothing to do.
        service.sweep(token = token(uid = "root"), dryRun = false).shouldBeRight().accounts.shouldBeEmpty()
        audit.list(actions = listOf(element = AuditAction.ACCOUNT_AUTO_DELETED), limit = 10, offset = 0).first.size shouldBe 1
    }

    @Test
    fun `the sweep reads the current threshold`() {
        admin()
        val fortyDays = signUp(uid = "forty", daysAgo = 40)
        settings.setStaleAccountDays(token = token(uid = "root"), days = 60).shouldBeRight()

        service.sweep(token = token(uid = "root"), dryRun = true).shouldBeRight().let {
            it.thresholdDays shouldBe 60
            it.accounts.shouldBeEmpty()
        }

        settings.setStaleAccountDays(token = token(uid = "root"), days = 30).shouldBeRight()
        service.sweep(token = token(uid = "root"), dryRun = true).shouldBeRight().accounts.map { it.userId } shouldContainExactly
            listOf(element = fortyDays.toString())
    }

    @Test
    fun `only the sweeper and administrators may run it as a person`() {
        signUp(uid = "sweeper", daysAgo = 400, roles = setOf(Capability.PLAYER, Capability.ACCOUNT_SWEEPER))
        signUp(uid = "manager", daysAgo = 400, roles = setOf(Capability.PLAYER, Capability.ACCOUNT_MANAGER))
        signUp(uid = "player", daysAgo = 400)
        admin()

        service.sweep(token = token(uid = "sweeper"), dryRun = true).shouldBeRight()
        service.sweep(token = token(uid = "root"), dryRun = true).shouldBeRight()
        // Restoring an account is ACCOUNT_MANAGER's job (#1002); deleting one is not.
        service.sweep(token = token(uid = "manager"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.sweep(token = token(uid = "player"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an ACCOUNT_SWEEPER key runs it, attributed to the client, and any other scope is refused`() {
        val stale = signUp(uid = "stale", daysAgo = 45)
        val client = ApiClientRepository().createClient(name = "Cloud Scheduler", createdBy = null).toDomain()
        val sweeperKey =
            ClientPrincipal(clientId = client.id, keyId = UUID.randomUUID(), scopes = setOf(element = Capability.ACCOUNT_SWEEPER))
        val pointsKey =
            ClientPrincipal(clientId = client.id, keyId = UUID.randomUUID(), scopes = setOf(element = Capability.POINTS_MANAGER))

        service.sweep(principal = pointsKey, dryRun = false).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        isActive(id = stale) shouldBe true

        service.sweep(principal = sweeperKey, dryRun = false).shouldBeRight().accounts.map { it.userId } shouldContainExactly
            listOf(element = stale.toString())
        val removal = audit.list(actions = listOf(element = AuditAction.ACCOUNT_AUTO_DELETED), limit = 10, offset = 0).first.single()
        removal.actorClientId shouldBe client.id
        removal.actorUserId shouldBe null
    }

    @Test
    fun `removal dates cover exactly the accounts the rule matches, whatever their age`() {
        val young = signUp(uid = "young", daysAgo = 10)
        val old = signUp(uid = "old", daysAgo = 45)
        val rated =
            signUp(
                uid = "rated",
                daysAgo = 45,
            ).also { RatingAssembler().setRating(userId = it, rating = BigDecimal("3.0"), level = "3.0") }

        service.removalDates(userIds = listOf(young, old, rated)) shouldContainExactly
            mapOf(
                young to now.minusDays(10).toLocalDate().plusDays(30),
                // Already past: the next run removes it.
                old to now.minusDays(45).toLocalDate().plusDays(30),
            )
        service.removalDates(userIds = emptyList()) shouldBe emptyMap()
    }
}
