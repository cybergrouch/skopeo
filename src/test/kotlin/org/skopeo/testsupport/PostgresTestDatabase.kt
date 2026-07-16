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
        started = true
    }

    /** Wipe the user cluster between tests (FK cascade clears children). */
    fun truncate() {
        transaction {
            exec("TRUNCATE users CASCADE")
            // app_settings isn't a child of users (updated_by is SET NULL), so reset the global
            // settings back to their V11 seed so theme state doesn't leak across tests (#378).
            exec(stmt = "TRUNCATE app_settings")
            exec(stmt = "INSERT INTO app_settings (key, value, updated_at) VALUES ('ui_theme', 'AUTO', now())")
        }
    }
}
