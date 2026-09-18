// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0"
    id("dev.detekt") version "2.0.0-alpha.6"
    jacoco
}

group = "org.skopeo"
version = "3.1.0-SNAPSHOT"
description = "Skopeo - Performance-based tennis rating system"

application {
    mainClass.set("org.skopeo.ApplicationKt")
}

// Single source of truth for the app version: generate version.properties from `project.version`
// onto the runtime classpath so /health reports it (no hardcoded literal). Release tags carry the
// official version by setting `version` on the tagged commit (see .github/workflows/release.yml).
// Plain `tasks.register(...)` rather than the `by tasks.registering` delegate: Gradle 9.6 deprecated
// the Kotlin DSL delegated-property syntax, and it is one of the two things that stood between this
// build and Gradle 10 on 9.7.1 (#1012). The delegate only ever bought us the task name for free —
// spelling it out is the whole of the migration, and the task itself is unchanged.
val generateVersionProperties =
    tasks.register("generateVersionProperties") {
        val versionFile = layout.buildDirectory.file("generated/version/version.properties")
        // `project.version` is read HERE, at configuration time, and the string is what the action
        // captures. Interpolating `project.version` inside `doLast` instead — as this did until #1012 —
        // is `Task.project` at execution time: deprecated, removed in Gradle 10, and unavailable under
        // the configuration cache, which Gradle is making the only mode. Same value either way, since
        // release tags set `version` on the tagged commit before any task runs.
        val projectVersion = project.version.toString()
        inputs.property("version", projectVersion)
        outputs.file(versionFile)
        doLast {
            versionFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText("version=$projectVersion\n")
            }
        }
    }
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}
tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}

val ktorVersion = "3.5.2"
val exposedVersion = "1.5.0"
val postgresVersion = "42.7.13"
val flywayVersion = "13.7.0"
val hikariVersion = "7.1.0"
val arrowVersion = "2.2.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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

    // Per-client rate limiting for partner API traffic (#225/#598)
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktorVersion")

    // The Firebase Admin SDK, added for ONE thing: writing live scores to Firestore as the spectator
    // broadcast channel (#911). The artifact also bundles Firebase Auth, and that half is deliberately
    // unused — ID tokens keep being verified against Google's public keys (JWKS) below, which needs no
    // SDK and, more importantly, no credentials.
    //
    // Nor does Firestore participate in authorization. Its rules *could* branch on request.auth and
    // custom claims, but our capabilities live in Postgres and #789's per-club rule cannot be expressed
    // as a claim at all. Mirroring them into claims would mean two systems to keep in step. So the
    // server authorizes every write and Firestore is read-only fan-out (see firestore.rules).
    //
    // The broadcast has to leave Cloud Run entirely. It runs --min-instances=1 --max-instances=2, so an
    // in-process SSE/WebSocket registry is broken by construction: the umpire's POST lands on one
    // instance while a spectator's stream is held by the other, and the event never crosses. Firestore
    // fans out server-push with no sockets to operate and no instance affinity.
    implementation("com.google.firebase:firebase-admin:9.10.0")

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
    implementation("ch.qos.logback:logback-classic:1.6.3")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    // Request id in the MDC and echoed on the response, so a user's screenshot reaches a log line (#805).
    implementation("io.ktor:ktor-server-call-id-jvm:$ktorVersion")
    // Backstop for exceptions that escape a route's own handling — without it they are a bare Ktor 500
    // that reaches no logger at all (#805).
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    // Structured JSON logs for Cloud Logging (#751). Micrometer/Prometheus was removed with the
    // /metrics endpoint: nothing scraped it (Cloud Run scales to zero, which suits pull-based scraping
    // badly), and per-endpoint metrics now come from log-based metrics over these fields.
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.5")
    testImplementation("io.kotest.extensions:kotest-assertions-arrow:2.0.0")
    // Mocking for the rare defensive path a real DB can't produce (e.g. a row deleted between an
    // existence check and its update); used sparingly — most service tests run against real Testcontainers.
    testImplementation("io.mockk:mockk:1.14.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Integration tests against a real PostgreSQL (applies the Flyway migration)
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")

    // Architecture tests — enforce layered package dependencies (issue #69). Bytecode-based, so
    // it's robust against Kotlin compiler versions (unlike source-scanning tools).
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // YAML parser for OpenAPIIntegrationTest (#401): parse the served spec so a malformed
    // documentation.yaml fails the backend gate instead of only breaking the web orval step.
    testImplementation("org.yaml:snakeyaml:2.7")

    // Sealed-hierarchy enumeration for LiveMatchEventKindContractTest (#989): `sealedSubclasses` is
    // declared in the stdlib but only implemented by kotlin-reflect, so without this the test compiles
    // and then dies at runtime with KotlinReflectionNotSupportedError. It arrives transitively today
    // (Ktor, Exposed), and a test whose entire job is to catch drift must not rest on that.
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// Parallel test forks (#978), OPT-IN via `SKOPEO_TEST_FORKS` and deliberately 1 by default.
//
// The suite serialises on one database, not on slow individual tests: 547s sequential across 162
// suites, with the slowest single suite at 52s. That 10.5x gap is what makes forking worth it — the
// profile (see #978) predicts ~137s at 4 forks, near-linear, because no one suite dominates.
//
// **The isolation model is unchanged, which is why this is safe.** `PostgresTestDatabase` is a Kotlin
// `object`, i.e. a per-JVM singleton: it starts its own container, migrates it, and truncates between
// tests. N Gradle forks are N JVMs, so they get N independent containers on Testcontainers-assigned
// ports with no shared state and no coordination. Nothing about the truncate-between-tests contract
// changes; there is simply more than one of it.
//
// **Default 1, not `availableProcessors()`, on purpose.** Each fork costs a Postgres container and a
// JVM. That is free on a CI runner and punishing on a constrained developer machine — the exact
// trade-off #978 flagged. An env var keeps local parallelism a deliberate act (`SKOPEO_TEST_FORKS=4
// ./gradlew test`) rather than something that silently saturates the machine this issue exists to
// unburden. CI sets it in `.github/workflows/ci.yml`.
val testForks =
    (System.getenv("SKOPEO_TEST_FORKS")?.toIntOrNull() ?: 1).coerceAtLeast(minimumValue = 1)

tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers' bundled docker-java defaults to Docker API v1.43, but Docker
    // Engine 25+ requires a minimum of v1.44 and rejects older calls with HTTP 400.
    // Pin the negotiated version so Testcontainers can reach the daemon (the value
    // sits within range for both local Docker Desktop and CI runners).
    systemProperty("api.version", "1.44")

    maxParallelForks = testForks
    if (testForks > 1) {
        logger.lifecycle("Running tests with $testForks parallel forks (SKOPEO_TEST_FORKS)")
    }
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
    // detekt 2.0 (#1008) models these as lazy Providers, so they are `.set(...)` rather than `=`.
    buildUponDefaultConfig.set(true)
    allRules.set(false)
    config.setFrom(files("$projectDir/detekt.yml"))
    baseline.set(file("$projectDir/detekt-baseline.xml"))
    // Replaces `build.maxIssues: 0` from the 1.x config, which 2.0 removed (#1008). maxIssues counted
    // EVERY finding regardless of severity, so `Info` — the lowest rung — is the faithful translation.
    // The 2.0 default is `Error`, which would have quietly stopped failing the build on warnings.
    failOnSeverity.set(dev.detekt.gradle.extensions.FailOnSeverity.Info)
}

// `detektMain` prints "There were 11 compiler errors found during analysis. This affects accuracy of
// reporting." That is EXPECTED and is not a misconfiguration here (#1017).
//
// All 11 are `unresolved reference 'serializer'`, in the three files that call a generated
// `Foo.serializer()` — Rating.kt, PointsConfigService.kt, PointsScheduleHistory.kt. That companion
// function is synthesised by the kotlinx.serialization COMPILER PLUGIN, and detekt's Analysis API
// frontend does not apply compiler plugins, so the reference cannot resolve. Upstream:
// https://github.com/detekt/detekt/issues/7531
//
// Verified rather than assumed: `enableCompilerPlugin = true` does NOT help (still 11), `detektTest`
// is unaffected (0), and the degradation is confined to those three files — so the type-aware rules
// are intact everywhere else. Do not "fix" this by adding a dependency; there is nothing to add.
//
// Do not swap `Foo.serializer()` for the library's `serializer<Foo>()` to appease the tool either:
// that trades a compile-time-guaranteed serializer for a reflective runtime lookup, in production
// serialization code, to quieten a linter. Wrong trade.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    // detekt 2.0 renamed two report types and dropped one (#1008): xml -> checkstyle, md -> markdown,
    // and the plain-text report is gone. Nothing in CI consumes any of these — they are all local
    // convenience — so the rename is the whole of the change and `txt` simply disappears.
    reports {
        checkstyle.required.set(true)
        html.required.set(true)
        sarif.required.set(true)
        markdown.required.set(true)
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
    toolVersion = "0.8.15"
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
                        // Mappers are pure dto↔model translation (like dto/), exercised via route/service tests.
                        "**/mapper/**",
                        "**/model/**",
                        // Neutral boundary value types under org.skopeo.common (ServiceError, auth
                        // principals, serializable contracts) — pure value types, like model/ and dto/.
                        "**/common/**",
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
                        // Handlers ARE tested (LiveMatchApiIntegrationTest, 14 tests over the real
                        // Firebase JWT path), but JaCoCo can't attribute coverage to the Ktor suspend
                        // route lambdas run in testApplication (see RankingRoutes). The scoring rules
                        // themselves are measured: ScoreEngine and LiveMatchService are not excluded.
                        "**/routes/LiveMatchRoutes*.*",
                        "**/routes/EventRoutes*.*",
                        // Same story as EventRoutes: the by-code handlers ARE tested
                        // (EventPublicViewApiIntegrationTest), but JaCoCo can't attribute coverage to the
                        // Ktor suspend route lambdas run in testApplication (see RankingRoutes).
                        "**/routes/EventByCodeRoutes*.*",
                        "**/routes/EventTeamRoutes*.*",
                        "**/routes/ClubRoutes*.*",
                        "**/routes/CircuitRoutes*.*",
                        "**/routes/PointsBudgetRoutes*.*",
                        "**/routes/InviteRoutes*.*",
                        "**/routes/AuditRoutes*.*",
                        // Handlers ARE tested (ApiClientApiIntegrationTest), but JaCoCo can't attribute
                        // coverage to the Ktor suspend route lambdas run in testApplication (see RankingRoutes).
                        "**/routes/ApiClientRoutes*.*",
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
                        "**/routes/FeatureFlagRoutes*.*",
                        "**/routes/PointsConfigRoutes*.*",
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
                    // The by-code event handlers (#741). Excluded by class name as well as by class
                    // directory: JaCoCo reports the Ktor suspend route lambdas under synthesized names
                    // (EventByCodeRoutesKt.publicEventByCode.1.1) that the path-based exclusion below
                    // does not always reach. Covered by EventPublicViewApiIntegrationTest.
                    "*.EventByCodeRoutesKt.*",
                    // Same story for live scoring (#911): JaCoCo reports the Ktor suspend route lambdas
                    // under synthesized names (LiveMatchRoutesKt.undo.1.1.1) that the path-based
                    // exclusion does not always reach. Covered by LiveMatchApiIntegrationTest.
                    "*.LiveMatchRoutesKt.*",
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
                        // Mappers are pure dto↔model translation (like dto/), exercised via route/service tests.
                        "**/mapper/**",
                        "**/model/**",
                        // Neutral boundary value types under org.skopeo.common (ServiceError, auth
                        // principals, serializable contracts) — pure value types, like model/ and dto/.
                        "**/common/**",
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
                        "**/routes/LiveMatchRoutes*.*",
                        "**/routes/EventRoutes*.*",
                        "**/routes/EventTeamRoutes*.*",
                        "**/routes/ClubRoutes*.*",
                        "**/routes/CircuitRoutes*.*",
                        "**/routes/PointsBudgetRoutes*.*",
                        "**/routes/InviteRoutes*.*",
                        "**/routes/AuditRoutes*.*",
                        // Handlers ARE tested (ApiClientApiIntegrationTest), but JaCoCo can't attribute
                        // coverage to the Ktor suspend route lambdas run in testApplication (see RankingRoutes).
                        "**/routes/ApiClientRoutes*.*",
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
                        "**/routes/FeatureFlagRoutes*.*",
                        "**/routes/PointsConfigRoutes*.*",
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
    mainClass.set("org.skopeo.domain.service.calculator.impl.v2.NtrpMatchupMatrixReportKt")
}

// Run the NTRP rating Monte Carlo simulation (a program, not a test). Writes
// /tmp/ntrp_montecarlo.txt and presentations/ntrp_montecarlo.md.  Usage: ./gradlew generateMonteCarloReport
tasks.register<JavaExec>("generateMonteCarloReport") {
    group = "reports"
    description = "Monte Carlo simulation of NTRP rating evolution over N matches"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.domain.service.calculator.impl.v2.NtrpMonteCarloReportKt")
}

// Run the doubles dominance study (a program, not a test). Shows how a doubles win's dominance and the
// winning pair's within-team NTRP gap split the rating change between partners. Writes
// /tmp/doubles_dominance.md and presentations/doubles_dominance.md.  Usage: ./gradlew generateDoublesDominanceReport
tasks.register<JavaExec>("generateDoublesDominanceReport") {
    group = "reports"
    description = "Doubles: effect of dominance + within-team gap on each partner's rating change"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.domain.service.calculator.impl.v2.DoublesDominanceReportKt")
}

// Monte Carlo study of the ranking-POINTS design (#525): expected steady-state leaderboard score
// and its cap, per player archetype and points-validity setting. Writes /tmp/points_ranking.txt and
// presentations/points_ranking.md.  Usage: ./gradlew generatePointsSimulationReport
tasks.register<JavaExec>("generatePointsSimulationReport") {
    group = "reports"
    description = "Monte Carlo simulation of ranking-points steady-state score and its cap"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("org.skopeo.domain.service.calculator.impl.v2.PointsRankingSimulationReportKt")
}
