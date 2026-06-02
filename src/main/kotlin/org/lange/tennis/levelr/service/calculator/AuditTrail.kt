package org.lange.tennis.levelr.service.calculator

/**
 * A single audit entry capturing a point in the calculation process.
 */
data class AuditEntry(
    val message: String,
    val context: Map<String, Any> = emptyMap(),
)

/**
 * Audit trail builder for collecting audit entries during calculation.
 * Simplified to have a single method - the AuditEntry itself captures the level.
 */
class AuditTrail {
    private val entries = mutableListOf<AuditEntry>()

    /**
     * Add an audit entry to the trail.
     */
    fun add(entry: AuditEntry) {
        entries.add(entry)
    }

    /**
     * Get all collected audit entries.
     */
    fun getEntries(): List<AuditEntry> = entries.toList()
}
