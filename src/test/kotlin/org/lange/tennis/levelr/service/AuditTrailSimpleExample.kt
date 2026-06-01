package org.lange.tennis.levelr.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Simple example demonstrating the AuditTrail API.
 * Shows how clean the single-method API is.
 */
class AuditTrailSimpleExample {
    @Test
    fun demonstrateSimpleAPI() {
        // Create an audit trail
        val audit = AuditTrail()

        // Add entries using the single method
        audit.add(AuditEntry(AuditLevel.INFO, "Starting calculation"))
        audit.add(AuditEntry(AuditLevel.DEBUG, "Step 1 complete", mapOf("value" to 42)))
        audit.add(AuditEntry(AuditLevel.INFO, "Calculation finished"))

        // Get all entries
        val entries = audit.getEntries()

        // Verify
        assertEquals(3, entries.size)
        assertEquals(AuditLevel.INFO, entries[0].level)
        assertEquals("Starting calculation", entries[0].message)
        assertEquals(42, entries[1].context["value"])
    }

    @Test
    fun demonstrateAuditEntryAsData() {
        // AuditEntry is just data - can create it anywhere
        val entry =
            AuditEntry(
                level = AuditLevel.INFO,
                message = "Test message",
                context = mapOf("key" to "value"),
            )

        // Can pass it around
        val trail = AuditTrail()
        trail.add(entry)

        // Can test it directly
        assertEquals(AuditLevel.INFO, entry.level)
        assertEquals("Test message", entry.message)
        assertEquals("value", entry.context["key"])
    }

    @Test
    fun demonstrateFilteringByLevel() {
        val audit = AuditTrail()

        // Add various levels
        audit.add(AuditEntry(AuditLevel.DEBUG, "Debug 1"))
        audit.add(AuditEntry(AuditLevel.INFO, "Info 1"))
        audit.add(AuditEntry(AuditLevel.DEBUG, "Debug 2"))
        audit.add(AuditEntry(AuditLevel.WARN, "Warning 1"))

        val entries = audit.getEntries()

        // Filter by level
        val debugEntries = entries.filter { it.level == AuditLevel.DEBUG }
        val infoEntries = entries.filter { it.level == AuditLevel.INFO }
        val warnEntries = entries.filter { it.level == AuditLevel.WARN }

        assertEquals(2, debugEntries.size)
        assertEquals(1, infoEntries.size)
        assertEquals(1, warnEntries.size)
    }

    @Test
    fun demonstrateContextAccess() {
        val audit = AuditTrail()

        // Add entry with rich context
        audit.add(
            AuditEntry(
                level = AuditLevel.INFO,
                message = "Player rating updated",
                context =
                    mapOf(
                        "playerId" to "P123",
                        "playerName" to "John Doe",
                        "oldRating" to 4.5,
                        "newRating" to 5.0,
                        "change" to 0.5,
                    ),
            ),
        )

        val entry = audit.getEntries().first()

        // Access structured context data
        assertEquals("P123", entry.context["playerId"])
        assertEquals("John Doe", entry.context["playerName"])
        assertEquals(4.5, entry.context["oldRating"])
        assertEquals(5.0, entry.context["newRating"])
        assertEquals(0.5, entry.context["change"])
    }
}
