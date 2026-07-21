// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
}

group = "org.skopeo"
version = "1.2.8-SNAPSHOT"
description = "Skopeo - Performance-based tennis rating system"

application {
    mainClass.set("org.skopeo.ApplicationKt")
}

// Single source of truth for the app version: generate version.properties from `project.version`
// onto the runtime classpath so /health reports it (no hardcoded literal). Release tags carry the
// official version by setting `version` on the tagged commit (see .github/workflows/release.yml).
val generateVersionProperties by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated/version/version.properties")
    inputs.property("version", project.version.toString())
    outputs.file(versionFile)
    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=${project.version}\n")
        }
    }
}
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}
tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}

val ktorVersion = "3.0.3"
val exposedVersion = "0.54.0"
val postgresVersion = "42.7.4"
val flywayVersion = "10.17.0"
val hikariVersion = "5.1.0"
val arrowVersion = "1.2.4"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")

    // Authentication — verify Firebase-issued JWTs against Google's public keys
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")

    // Swagger UI for interactive API documentation
    implementation("io.ktor:ktor-server-swagger:$ktorVersion")

    // Database - PostgreSQL
    implementation("org.postgresql:postgresql:$postgresVersion")

    // Database - Flyway migrations
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Database - Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // Database - Connection pooling
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Functional error handling — repositories and services return Either<ServiceError, T> (issue #115)
    implementation("io.arrow-kt:arrow-core:$arrowVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Metrics
    implementation("io.ktor:ktor-server-metrics-micrometer-jvm:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest.extensions:kotest-assertions-arrow:1.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Integration tests against a real PostgreSQL (applies the Flyway migration)
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")

    // Architecture tests — enforce layered package dependencies (issue #69). Bytecode-based, so
    // it's robust against Kotlin compiler versions (unlike source-scanning tools).
    testImplementation("com.tngtech.archunit:archunit:1.3.0")

    // YAML parser for OpenAPIIntegrationTest (#401): parse the served spec so a malformed
    // documentation.yaml fails the backend gate instead of only breaking the web orval step.
    testImplementation("org.yaml:snakeyaml:2.3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers' bundled docker-java defaults to Docker API v1.43, but Docker
    // Engine 25+ requires a minimum of v1.44 and rejects older calls with HTTP 400.
    // Pin the negotiated version so Testcontainers can reach the daemon (the value
    // sits within range for both local Docker Desktop and CI runners).
    systemProperty("api.version", "1.44")
}

// ktlint configuration
ktlint {
    version.set("1.0.1")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

// detekt configuration
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$projectDir/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(true)
        sarif.required.set(true)
        md.required.set(true)
    }
}

// Enforce detekt WITH type resolution. The default `detekt` task runs without a classpath,
// so type-resolution-only rules (e.g. NamedArguments, UnsafeCallOnNullableType) are silently
// skipped. `detektMain`/`detektTest` analyze with the compile classpath, so `check`/CI catch them.
tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

// Install Git pre-commit hook that runs ktlint format
tasks.register("installGitHooks") {
    description = "Install Git pre-commit hook for automatic code formatting"
    group = "git hooks"

    doLast {
        val hooksDir = file(".git/hooks")
        if (!hooksDir.exists()) {
            println("⚠️  .git/hooks directory not found. Make sure you're in a git repository.")
            return@doLast
        }

        val preCommitFile = file(".git/hooks/pre-commit")
        preCommitFile.writeText(
            """
            #!/bin/bash
            # Auto-format code before commit

            # Block staged secrets (API keys, tokens, credentials) before they are committed.
            # Mirrors the CI "Secret scan" job. No-op (with a notice) if gitleaks isn't installed.
            if command -v gitleaks >/dev/null 2>&1; then
                echo "🔑 Scanning staged changes for secrets (gitleaks)..."
                if ! gitleaks protect --staged --redact --config .gitleaks.toml; then
                    echo "❌ gitleaks found a potential secret in staged changes."
                    echo "   Remove/unstage it (real keys belong only in git-ignored env files) and retry."
                    exit 1
                fi
            else
                echo "ℹ️  gitleaks not installed — skipping local secret scan (CI still enforces it)."
                echo "   Install it to catch secrets before pushing: https://github.com/gitleaks/gitleaks#installing"
            fi

            echo "🎨 Running ktlint format..."
            ./gradlew ktlintFormat --quiet

            # Check if formatting introduced any changes
            if ! git diff --quiet; then
                echo "✅ Code formatted. Changes auto-staged."
                git add -u
            fi

            # Verify code style
            echo "🔍 Checking code style..."
            if ! ./gradlew ktlintCheck --quiet; then
                echo "❌ ktlint check failed. Please fix the issues and try again."
                exit 1
            fi

            echo "✅ Code style check passed!"
            exit 0
            """.trimIndent(),
        )

        // Make it executable
        preCommitFile.setExecutable(true)

        println("✅ Git pre-commit hook installed successfully!")
        println("   Location: .git/hooks/pre-commit")
        println("   The hook will automatically format code before each commit.")
    }
}

// Uninstall Git hooks
tasks.register("uninstallGitHooks") {
    description = "Remove Git pre-commit hook"
    group = "git hooks"

    doLast {
        val preCommitFile = file(".git/hooks/pre-commit")
        if (preCommitFile.exists()) {
            preCommitFile.delete()
            println("✅ Git pre-commit hook removed")
        } else {
            println("ℹ️  No pre-commit hook found")
        }
    }
}

// JaCoCo configuration for code coverage
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // Tests are required to run before generating the report

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)

        xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml"))
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/test/html"))
    }

    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // Exclude data classes, DTOs, and generated code from coverage
                    exclude(
                        "**/dto/**",
                        "**/model/**",
                        "**/*Application*.*",
                        // Database wiring requires a live PostgreSQL instance
                        "**/config/**",
                        // Kotlin file-level functions
                        "**/*Kt.class",
                        // Auth & route wiring — happy path needs a Firebase token
                        // (covered via the emulator once provisioning lands)
                        "**/Security*.*",
                        "**/routes/UserRoutes*.*",
                        "**/routes/PlayerRoutes*.*",
                        "**/routes/ContactRoutes*.*",
                        "**/routes/NameRoutes*.*",
                        "**/routes/CapabilityRoutes*.*",
                        "**/routes/RatingRoutes*.*",
                        "**/routes/RatingRequestRoutes*.*",
                        "**/routes/MatchRoutes*.*",
                        "**/routes/EventRoutes*.*",
                        "**/routes/ClubRoutes*.*",
                        "**/routes/PointsBudgetRoutes*.*",
                        "**/routes/InviteRoutes*.*",
                        "**/routes/AuditRoutes*.*",
                        "**/routes/DuplicateCandidateRoutes*.*",
                        "**/routes/PlayerListRoutes*.*",
                        // Handlers ARE tested (PlaceholderApiIntegrationTest), but JaCoCo can't attribute
                        // coverage to the Ktor suspend route lambdas run in testApplication (see RankingRoutes).
                        "**/routes/PlaceholderRoutes*.*",
                        "**/routes/StandingsRoutes*.*",
                        "**/routes/StandingsCalculationRoutes*.*",
                        "**/routes/RankingPointRoutes*.*",
                        "**/routes/ReportRoutes*.*",
                        "**/routes/ThemeRoutes*.*",
                        "**/routes/StandingsSourceRoutes*.*",
                        // Handler IS tested (RankingCalculationApiErrorTest), but JaCoCo can't
                        // attribute coverage to the Ktor suspend route lambda run in testApplication.
                        "**/routes/RankingRoutes*.*",
                        // Only the route glue (OpenGraphRoutes); the pure OG logic in OpenGraph.kt stays measured.
                        "**/routes/OpenGraphRoutes*.*",
                        "**/routes/RouteSupport*.*",
                    )
                }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)

    violationRules {
        rule {
            limit {
                minimum = "0.75".toBigDecimal() // 75% line coverage minimum
            }
        }

        rule {
            element = "CLASS"
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal() // 70% branch coverage minimum
            }

            // Exclude exception handling blocks which are hard to test
            // Route error handling lambdas
            excludes =
                listOf(
                    "*.configureRankingRoutes.*",
                )
        }
    }

    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // Same exclusions as the report
                    exclude(
                        "**/dto/**",
                        "**/model/**",
                        "**/*Application*.*",
                        "**/config/**",
                        "**/*Kt.class",
                        "**/Security*.*",
                        "**/routes/UserRoutes*.*",
                        "**/routes/PlayerRoutes*.*",
                        "**/routes/ContactRoutes*.*",
                        "**/routes/NameRoutes*.*",
                        "**/routes/CapabilityRoutes*.*",
                        "**/routes/RatingRoutes*.*",
                        "**/routes/RatingRequestRoutes*.*",
                        "**/routes/MatchRoutes*.*",
                        "**/routes/EventRoutes*.*",
                        "**/routes/ClubRoutes*.*",
                        "**/routes/PointsBudgetRoutes*.*",
                        "**/routes/InviteRoutes*.*",
                        "**/routes/AuditRoutes*.*",
                        "**/routes/DuplicateCandidateRoutes*.*",
                        "**/routes/PlayerListRoutes*.*",
                        // Handlers ARE tested (PlaceholderApiIntegrationTest), but JaCoCo can't attribute
                        // coverage to the Ktor suspend route lambdas run in testApplication (see RankingRoutes).
                        "**/routes/PlaceholderRoutes*.*",
                        "**/routes/StandingsRoutes*.*",
                        "**/routes/StandingsCalculationRoutes*.*",
                        "**/routes/RankingPointRoutes*.*",
                        "**/routes/ReportRoutes*.*",
                        "**/routes/ThemeRoutes*.*",
                        "**/routes/StandingsSourceRoutes*.*",
                        // Handler IS tested (RankingCalculationApiErrorTest), but JaCoCo can't
                        // attribute coverage to the Ktor suspend route lambda run in testApplication.
                        "**/routes/RankingRoutes*.*",
                        // Only the route glue (OpenGraphRoutes); the pure OG logic in OpenGraph.kt stays measured.
                        "**/routes/OpenGraphRoutes*.*",
                        "**/routes/RouteSupport*.*",
                    )
                }
            },
        ),
    )
}

// Make check task depend on coverage verification
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Automatically generate coverage report after tests
tasks.test {
    finalizedBy(tasks.jacocoTestReport) // Report is always generated after tests run
}

// Database migrations:
// - Runtime: the app runs Flyway (flyway-core) on startup via DatabaseConfig.init,
//   so migrations apply automatically on `./gradlew run`, in Docker, and on Cloud Run.
// - Manual/ad-hoc: use the Flyway CLI Docker image (see docs/engineering/operations/database-setup.md).
// The official Flyway *Gradle plugin* is intentionally NOT used — it relies on
// JavaPluginConvention, removed in Gradle 9, and is effectively unmaintained.

// Run the NTRP matchup matrix report (a program, not a test — lives in test sources because
// it reuses the test calculator helpers). Writes /tmp/ntrp_matchup_matrix.txt and
// presentations/ntrp_matchup_matrix.md.  Usage: ./gradlew generateMatchupReport
tasks.register<JavaExec>("generateMatchupReport") {
    group = "reports"
    description = "Generate the NTRP matchup matrix report (text + Markdown)"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.service.calculator.impl.v2.NtrpMatchupMatrixReportKt")
}

// Run the NTRP rating Monte Carlo simulation (a program, not a test). Writes
// /tmp/ntrp_montecarlo.txt and presentations/ntrp_montecarlo.md.  Usage: ./gradlew generateMonteCarloReport
tasks.register<JavaExec>("generateMonteCarloReport") {
    group = "reports"
    description = "Monte Carlo simulation of NTRP rating evolution over N matches"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.service.calculator.impl.v2.NtrpMonteCarloReportKt")
}

// Run the doubles dominance study (a program, not a test). Shows how a doubles win's dominance and the
// winning pair's within-team NTRP gap split the rating change between partners. Writes
// /tmp/doubles_dominance.md and presentations/doubles_dominance.md.  Usage: ./gradlew generateDoublesDominanceReport
tasks.register<JavaExec>("generateDoublesDominanceReport") {
    group = "reports"
    description = "Doubles: effect of dominance + within-team gap on each partner's rating change"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.service.calculator.impl.v2.DoublesDominanceReportKt")
}
