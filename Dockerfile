# Multi-stage Dockerfile for Skopeo API
# Stage 1: Build stage with full JDK and Gradle
# Stage 2: Runtime stage with JRE only

# ============================================================================
# Stage 1: Build
# ============================================================================
# The builder runs on Java 25, matching the daemon toolchainVersion pinned in
# gradle/gradle-daemon-jvm.properties — which is COPYd in below, so this base image MUST track
# it. If they diverge the daemon cannot start here at all, and auto-download is off (see below),
# so there is no silent fallback. It was 21 until detekt 2.0 lifted the Java 25 ceiling (#1008).
#
# Since #1030 the compile toolchain is ALSO Java 25, so this base image satisfies it directly and
# the separate `jdk17` stage that used to be COPYd in is gone. That stage existed only because a
# 17 toolchain would otherwise be auto-downloaded from adoptium/foojay on every build — a flaky
# network dependency (#280). One base image now covers daemon and toolchain both; auto-download
# stays off so a mismatch fails loudly instead of silently reaching the network.
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

ENV GRADLE_TOOLCHAIN_ARGS="-Dorg.gradle.java.installations.auto-download=false"

# Copy Gradle wrapper and build files first (for caching)
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./

# Download dependencies (cached if build files unchanged)
RUN ./gradlew dependencies --no-daemon $GRADLE_TOOLCHAIN_ARGS

# Copy source code
COPY src/ src/

# Build the application distribution (skip tests and checks - run in CI)
RUN ./gradlew clean installDist --no-daemon $GRADLE_TOOLCHAIN_ARGS

# ============================================================================
# Stage 2: Runtime
# ============================================================================
# Debian (glibc), NOT Alpine (musl) — and this is load-bearing.
#
# firebase-admin (#911) pulls in gRPC, which ships netty-tcnative as a PRECOMPILED NATIVE .so built
# against glibc. Loading it on musl segfaults the JVM during startup, in
# netty_internal_tcnative_SSLContext_JNI_OnLoad, before the server ever listens — so the container
# crash-loops with no Kotlin stack trace to explain it.
#
# The host test suite cannot catch this: it runs on the developer's JVM and never enters the image.
# Anything that adds a dependency with bundled native code has to be exercised in the container.
#
# Java 25 since #1008 (was 17). This is the PRODUCTION JVM — the only one of the project's three that
# users actually touch.
#
# ⚠️ This line is NO LONGER independently revertible, and that changed deliberately in #1030. While
# the compile toolchain stayed at Java 17 the bytecode was class-file major 61 and ran on a 17 OR a
# 25 JRE, so swapping this base image back needed no rebuild. The toolchain is now 25, the bytecode
# is major 69, and it will NOT load on a 17 JRE. Going back means moving the toolchain back too and
# rebuilding — a code change, not a one-line image swap. That was an accepted trade: the runtime had
# already been on 25 since #1008 with no plan to return, and Java 17 left Oracle premier support on
# 30 Sep 2026.
FROM eclipse-temurin:25-jre-noble

LABEL maintainer="Skopeo Team"
LABEL description="Skopeo API - Dynamic tennis ranking calculation service"
LABEL version="1.0"

WORKDIR /app

# Create non-root user for security. Debian's adduser, not Alpine's BusyBox applet.
RUN groupadd --system appgroup && useradd --system --gid appgroup --no-create-home appuser \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the application distribution from build stage
COPY --from=builder /build/build/install/skopeo .

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Health check using the /health endpoint. curl is installed above because the Debian JRE image, unlike
# the Alpine one, ships neither wget nor curl — an unfixed HEALTHCHECK would report the container
# permanently unhealthy while the app ran perfectly.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl --fail --silent --show-error http://localhost:8080/health || exit 1

# Run the application using the startup script
ENTRYPOINT ["/app/bin/skopeo"]
