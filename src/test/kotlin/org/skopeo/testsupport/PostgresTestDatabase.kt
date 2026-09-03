// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A single PostgreSQL container shared across all DB-backed tests (the Testcontainers
 * "singleton container" pattern). Started once on first use with the real Flyway V1
 * migration applied and Exposed connected; reaped by Ryuk when the JVM exits.
 */
object PostgresTestDatabase {
    private val container =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("skopeo_test")

    private var started = false

    /**
     * The points-schedule seed exactly as the migrations wrote it (#862), captured once before any test
     * can disturb it and re-inserted by [truncate].
     *
     * Needed because `TRUNCATE users CASCADE` truncates **every table with a foreign key referencing
     * users, regardless of its ON DELETE action** — so `points_schedule_versions.created_by` drags the
     * version rows into the wipe, and `points_config` and `ranking_point_awards` follow from there. With
     * the version gone, awarding fails with "points_schedule_versions is unseeded".
     *
     * Captured rather than re-derived from `PointsConfigContract.DEFAULT`: re-inserting the Kotlin
     * defaults here would make `PointsScheduleSeedTest` compare the defaults against a copy of themselves
     * — tautologically green — whenever another test class had truncated first. Snapshotting the migrated
     * rows keeps that guard comparing code against what the *migration* actually seeds.
     */
    private var scheduleSeed: List<Pair<Int, Pair<String, String>>> = emptyList()
    private var seedVersions: List<Int> = emptyList()

    @Synchronized
    fun start() {
        if (started) return
        container.start()
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
        captureScheduleSeed()
        started = true
    }

    /** Read the migrated points-schedule rows into memory so [truncate] can put them back verbatim. */
    private fun captureScheduleSeed() {
        transaction {
            seedVersions =
                exec(stmt = "SELECT version FROM points_schedule_versions WHERE is_current ORDER BY version") { rs ->
                    buildList { while (rs.next()) add(element = rs.getInt(1)) }
                }.orEmpty()
            scheduleSeed =
                exec(stmt = "SELECT version, key, value FROM points_config ORDER BY version, key") { rs ->
                    buildList {
                        while (rs.next()) {
                            add(element = rs.getInt(1) to (rs.getString(2) to rs.getString(3)))
                        }
                    }
                }.orEmpty()
        }
    }

    /** Put the captured points-schedule seed back after a wipe (see [scheduleSeed]). */
    private fun restoreScheduleSeed() {
        transaction {
            seedVersions.forEach { version ->
                exec(stmt = "INSERT INTO points_schedule_versions (version, is_current) VALUES ($version, TRUE)")
            }
            scheduleSeed.forEach { (version, document) ->
                val (key, value) = document
                exec(
                    stmt =
                        "INSERT INTO points_config (version, key, value, updated_at) " +
                            "VALUES ($version, '$key', '$value', CURRENT_TIMESTAMP)",
                )
            }
        }
    }

    /** Wipe the user cluster between tests (FK cascade clears children). */
    fun truncate() {
        transaction {
            exec("TRUNCATE users CASCADE")
            // standings_snapshots isn't a child of users (only its entries are), so it survives the
            // cascade above; truncate it explicitly (cascading to standings_entries) so a snapshot from
            // one test doesn't leak into the next (#220).
            exec(stmt = "TRUNCATE standings_snapshots CASCADE")
            // app_settings isn't a child of users (updated_by is SET NULL), so reset the global
            // settings back to their V11 seed so theme state doesn't leak across tests (#378).
            exec(stmt = "TRUNCATE app_settings")
            exec(stmt = "INSERT INTO app_settings (key, value, updated_at) VALUES ('ui_theme', 'AUTO', now())")
            // api_clients only SET NULL on the users FK (not CASCADE), so it survives the users wipe;
            // truncate it explicitly (cascading to api_keys) so keys from one test don't leak (#225/#596).
            exec(stmt = "TRUNCATE api_clients CASCADE")
            // The per-club points-budget table was removed with the budget/designation subsystem (#559).
        }
        // points_schedule_versions references users, so TRUNCATE ... CASCADE takes it (and points_config)
        // with it whatever its ON DELETE action says. Put the migrated seed back, or every award fails
        // for want of a current schedule version (#862).
        restoreScheduleSeed()
    }
}
