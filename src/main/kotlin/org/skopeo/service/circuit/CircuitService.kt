// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.circuit

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Circuit
import org.skopeo.model.CreateCircuitCommand
import org.skopeo.model.ServiceError
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/** Roles that may read the circuit list (e.g. to pick a circuit when creating a tournament, #525). */
private val CIRCUIT_STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Admin-only management of circuits (#525): create, rename, and soft-delete circuits (e.g. NORTH,
 * SOUTH). Reads are staff-visible so tournament organizers can pick a circuit. Mirrors [ClubService]
 * minus the owner association. Expected failures are returned as an [Either] left ([ServiceError],
 * issue #115) rather than thrown.
 */
class CircuitService(
    private val circuits: CircuitRepository = CircuitRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, Circuit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Circuit name is required") }
            val circuit = circuits.create(command = CreateCircuitCommand(name = name.trim(), createdBy = adminId))
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CIRCUIT_CREATED,
                        entityType = AuditEntityType.CIRCUIT,
                        entityId = circuit.id,
                        summary = "Created circuit ${circuit.name}",
                        details = mapOf("circuitId" to circuit.id.toString(), "name" to circuit.name),
                    ),
            )
            circuit
        }

    /** Readable by staff (HOST/CLUB_OWNER/ADMINISTRATOR) so tournament organizers can pick a circuit (#525). */
    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<Circuit>> =
        either {
            requireStaff(token = token).bind()
            circuits.list()
        }

    /** Rename a circuit (#525). ADMINISTRATOR-only; the name is validated (non-blank) and trimmed. */
    fun rename(
        token: VerifiedFirebaseToken,
        circuitId: UUID,
        name: String,
    ): Either<ServiceError, Circuit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Circuit name is required") }
            val updated =
                ensureNotNull(value = circuits.rename(id = circuitId, name = name.trim())) {
                    ServiceError.NotFound(message = "Circuit $circuitId not found")
                }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CIRCUIT_RENAMED,
                        entityType = AuditEntityType.CIRCUIT,
                        entityId = circuitId,
                        summary = "Renamed circuit to ${updated.name}",
                        details = mapOf("circuitId" to circuitId.toString(), "name" to updated.name),
                    ),
            )
            updated
        }

    /**
     * Delete a circuit (#525). ADMINISTRATOR-only. A soft-delete (is_active → false), like clubs: the
     * row is retired rather than removed, so it drops out of the list but its history and any future
     * tournament references stay intact. Deleting a missing or already-deleted circuit is a NotFound.
     */
    fun delete(
        token: VerifiedFirebaseToken,
        circuitId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val circuit =
                ensureNotNull(value = circuits.findById(id = circuitId)) {
                    ServiceError.NotFound(message = "Circuit $circuitId not found")
                }
            ensure(condition = circuits.disable(id = circuitId)) { ServiceError.NotFound(message = "Circuit $circuitId not found") }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CIRCUIT_DELETED,
                        entityType = AuditEntityType.CIRCUIT,
                        entityId = circuitId,
                        summary = "Deleted circuit ${circuit.name}",
                        details = mapOf("circuitId" to circuitId.toString(), "name" to circuit.name),
                    ),
            )
        }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }

    /** Staff (HOST/CLUB_OWNER/ADMINISTRATOR) access, for reads like the circuit list. */
    private fun requireStaff(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isStaff = caller != null && caller.capabilities.any { it in CIRCUIT_STAFF_ROLES }
        return if (caller == null || !isStaff) ServiceError.Forbidden().left() else caller.id.right()
    }
}
