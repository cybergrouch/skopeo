# Multi-stage Dockerfile for Tennis Levelr API
# Stage 1: Build stage with full JDK and Gradle
# Stage 2: Runtime stage with JRE only

# ============================================================================
# Stage 1: Build
# ============================================================================
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Gradle wrapper and build files first (for caching)
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./

# Download dependencies (cached if build files unchanged)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application distribution (skip tests and checks - run in CI)
RUN ./gradlew clean installDist --no-daemon

# ============================================================================
# Stage 2: Runtime
# ============================================================================
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Tennis Levelr Team"
LABEL description="Tennis Levelr API - Dynamic tennis ranking calculation service"
LABEL version="1.0"

WORKDIR /app

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the application distribution from build stage
COPY --from=builder /build/build/install/tennis_levlr .

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Health check using the /health endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run the application using the startup script
ENTRYPOINT ["/app/bin/tennis_levlr"]
