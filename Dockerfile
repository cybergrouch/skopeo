# Multi-stage Dockerfile for Skopeo API
# Stage 1: Build stage with full JDK and Gradle
# Stage 2: Runtime stage with JRE only

# A local JDK 17 to satisfy the compile toolchain without a build-time download (#280).
FROM eclipse-temurin:17-jdk AS jdk17

# ============================================================================
# Stage 1: Build
# ============================================================================
# The builder runs on Java 21 (the Gradle daemon launcher pinned in
# gradle/gradle-daemon-jvm.properties). The project's compile toolchain is Java 17
# (build.gradle.kts), which Gradle would otherwise auto-download from adoptium/foojay on
# every build — a flaky network dependency (#280). Copy a JDK 17 in and hand it to Gradle
# via org.gradle.java.installations.paths so the toolchain resolves locally, no download.
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk-17
ENV GRADLE_TOOLCHAIN_ARGS="-Dorg.gradle.java.installations.paths=/opt/java/openjdk-17 -Dorg.gradle.java.installations.auto-download=false"

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
FROM eclipse-temurin:17-jre-noble

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
