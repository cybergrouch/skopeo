plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
}

group = "org.lange.tennis.levelr"
version = "0.0.1-SNAPSHOT"
description = "tennis_levelr"

application {
    mainClass.set("org.lange.tennis.levelr.ApplicationKt")
}

val ktorVersion = "3.0.3"

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
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
                        // Kotlin file-level functions
                        "**/*Kt.class",
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
                        "**/*Kt.class",
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
