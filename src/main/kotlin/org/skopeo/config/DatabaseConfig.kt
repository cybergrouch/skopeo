// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import mu.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * Database configuration and initialization.
 *
 * Handles:
 * - HikariCP connection pooling
 * - Flyway database migrations
 * - Exposed ORM connection setup
 */
object DatabaseConfig {
    private var dataSource: HikariDataSource? = null

    /**
     * Initialize the database connection and run migrations.
     *
     * @param application Ktor application instance for reading configuration
     */
    fun init(application: Application) {
        logger.info { "Initializing database configuration..." }

        // Read database configuration from application.yaml
        val dbUrl = application.environment.config.property("database.url").getString()
        val dbDriver = application.environment.config.property("database.driver").getString()
        val dbUser = application.environment.config.property("database.user").getString()
        val dbPassword = application.environment.config.property("database.password").getString()

        // Connection pool configuration
        val poolMaxSize =
            application.environment.config.propertyOrNull("database.pool.maximumPoolSize")
                ?.getString()?.toInt() ?: 10
        val poolMinIdle =
            application.environment.config.propertyOrNull("database.pool.minimumIdle")
                ?.getString()?.toInt() ?: 2
        val connectionTimeout =
            application.environment.config.propertyOrNull("database.pool.connectionTimeout")
                ?.getString()?.toLong() ?: 30000
        val idleTimeout =
            application.environment.config.propertyOrNull("database.pool.idleTimeout")
                ?.getString()?.toLong() ?: 600000
        val maxLifetime =
            application.environment.config.propertyOrNull("database.pool.maxLifetime")
                ?.getString()?.toLong() ?: 1800000

        logger.info { "Database URL: $dbUrl" }
        logger.info { "Database driver: $dbDriver" }
        logger.info { "Connection pool size: $poolMaxSize (min idle: $poolMinIdle)" }

        // Create HikariCP data source
        dataSource =
            createDataSource(
                url = dbUrl,
                driver = dbDriver,
                user = dbUser,
                password = dbPassword,
                maximumPoolSize = poolMaxSize,
                minimumIdle = poolMinIdle,
                connectionTimeout = connectionTimeout,
                idleTimeout = idleTimeout,
                maxLifetime = maxLifetime,
            )

        // Run Flyway migrations
        runMigrations(dataSource!!)

        // Connect Exposed ORM
        Database.connect(dataSource!!)

        logger.info { "Database initialization complete" }
    }

    /**
     * Create HikariCP data source with connection pooling.
     */
    private fun createDataSource(
        url: String,
        driver: String,
        user: String,
        password: String,
        maximumPoolSize: Int,
        minimumIdle: Int,
        connectionTimeout: Long,
        idleTimeout: Long,
        maxLifetime: Long,
    ): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = driver
                username = user
                this.password = password
                this.maximumPoolSize = maximumPoolSize
                this.minimumIdle = minimumIdle
                this.connectionTimeout = connectionTimeout
                this.idleTimeout = idleTimeout
                this.maxLifetime = maxLifetime

                // HikariCP performance tuning
                isAutoCommit = true
                transactionIsolation = "TRANSACTION_READ_COMMITTED"

                // Connection testing
                connectionTestQuery = "SELECT 1"
                validationTimeout = 5000

                // Pool name for monitoring
                poolName = "SkopeoPool"
            }

        return HikariDataSource(config)
    }

    /**
     * Run Flyway database migrations.
     *
     * @param dataSource Data source to run migrations against
     */
    private fun runMigrations(dataSource: DataSource) {
        logger.info { "Running Flyway database migrations..." }

        val flyway =
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()

        try {
            val migrationsApplied = flyway.migrate()
            logger.info { "Flyway migrations complete. ${migrationsApplied.migrationsExecuted} migrations applied." }
        } catch (e: Exception) {
            logger.error(e) { "Flyway migration failed" }
            throw e
        }
    }

    /**
     * Close the database connection pool.
     * Should be called when the application shuts down.
     */
    fun close() {
        logger.info { "Closing database connection pool..." }
        dataSource?.close()
        logger.info { "Database connection pool closed" }
    }
}
