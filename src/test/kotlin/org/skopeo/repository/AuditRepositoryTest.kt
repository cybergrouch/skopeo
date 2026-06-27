// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class AuditRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val audit = AuditRepository()
    private val users = UserRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionAdmin(): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "admin",
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "admin", isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        ).id

    @Test
    fun `records an entry and reads it back with details (incl a null value) round-tripped`() {
        val actor = provisionAdmin()
        val target = UUID.randomUUID()
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.RATING_SET,
                    entityType = AuditEntityType.RATING,
                    entityId = target,
                    summary = "Set rating to 4.0",
                    details = mapOf("newRating" to "4.000000", "previousRating" to null),
                ),
        )

        val entry = audit.list(action = null, limit = 10, offset = 0).first.single()
        entry.actorUserId shouldBe actor
        entry.action shouldBe AuditAction.RATING_SET
        entry.entityType shouldBe AuditEntityType.RATING
        entry.entityId shouldBe target
        entry.summary shouldBe "Set rating to 4.0"
        entry.details["newRating"] shouldBe "4.000000"
        entry.details.containsKey(key = "previousRating").shouldBeTrue()
        entry.details["previousRating"].shouldBeNull() // null JSON value survives the round-trip
        entry.comment.shouldBeNull()
    }

    @Test
    fun `a system action has a null actor and empty details`() {
        audit.record(
            write =
                AuditWrite(
                    actorUserId = null,
                    action = AuditAction.INVITE_REVOKED,
                    entityType = AuditEntityType.INVITE,
                    entityId = UUID.randomUUID(),
                    summary = "Revoked invite x@y.dev",
                ),
        )
        val entry = audit.list(action = null, limit = 10, offset = 0).first.single()
        entry.actorUserId.shouldBeNull()
        entry.details shouldBe emptyMap()
    }

    @Test
    fun `list filters by action and paginates with a total`() {
        val actor = provisionAdmin()
        repeat(times = 2) {
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actor,
                        action = AuditAction.CAPABILITY_GRANTED,
                        entityType = AuditEntityType.CAPABILITY,
                        entityId = UUID.randomUUID(),
                        summary = "Granted HOST role",
                    ),
            )
        }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.INVITE_CREATED,
                    entityType = AuditEntityType.INVITE,
                    entityId = UUID.randomUUID(),
                    summary = "Invited a@b.dev",
                ),
        )

        val (granted, grantedTotal) = audit.list(action = AuditAction.CAPABILITY_GRANTED, limit = 1, offset = 0)
        grantedTotal shouldBe 2L
        granted shouldHaveSize 1
        granted.single().action shouldBe AuditAction.CAPABILITY_GRANTED

        audit.list(action = null, limit = 10, offset = 0).second shouldBe 3L
    }

    @Test
    fun `the jsonb column type maps text both ways, treating a null payload as empty`() {
        val type = JsonbColumnType()
        (type.valueToDB(value = "{\"a\":\"b\"}") as PGobject).value shouldBe "{\"a\":\"b\"}"
        type.valueFromDB(
            value =
                PGobject().apply {
                    this.type = "jsonb"
                    value = "{\"a\":\"b\"}"
                },
        ) shouldBe "{\"a\":\"b\"}"
        // A jsonb object with no value reads back as an empty string (the defensive null branch).
        type.valueFromDB(
            value =
                PGobject().apply {
                    this.type = "jsonb"
                    value = null
                },
        ) shouldBe ""
    }

    @Test
    fun `updateComment sets the note, and reports a missing entry`() {
        val actor = provisionAdmin()
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.RATING_OVERRIDDEN,
                    entityType = AuditEntityType.RATING,
                    entityId = UUID.randomUUID(),
                    summary = "Overrode rating 4.0 → 4.5",
                ),
        )
        val id = audit.list(action = null, limit = 1, offset = 0).first.single().id

        audit.updateComment(id = id, comment = "Corrected after review").shouldBeTrue()
        audit.list(action = null, limit = 1, offset = 0).first.single().comment shouldBe "Corrected after review"

        audit.updateComment(id = UUID.randomUUID(), comment = "x").shouldBeFalse()
    }
}
