# Docker Deployment Guide

## Overview

This guide covers deploying Skopeo API using Docker containers. The application is packaged as a Docker image using a multi-stage build for optimal size and security.

**Image Details:**
- Base: Alpine Linux with OpenJDK 17 JRE
- Size: ~200MB (runtime image)
- Port: 8080
- Health Check: Built-in using `/health` endpoint
- User: Non-root (appuser)

---

## Quick Start

### Using Docker

```bash
# Build the image
docker build -t skopeo .

# Run the container
docker run -d -p 8080:8080 --name skopeo skopeo

# Test the API
curl http://localhost:8080/health

# View logs
docker logs skopeo

# Stop the container
docker stop skopeo
docker rm skopeo
```

### Using Docker Compose

```bash
# Start the service
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the service
docker-compose down
```

---

## Building the Image

### Basic Build

```bash
docker build -t skopeo .
```

### Build with Version Tag

```bash
docker build -t skopeo:1.0.0 .
```

### Build with No Cache (Clean Build)

```bash
docker build --no-cache -t skopeo .
```

### Using the Helper Script

```bash
# Build with version tag
./scripts/docker-build.sh 1.0.0

# Build as latest
./scripts/docker-build.sh
```

### Build Process

The Dockerfile uses a **multi-stage build**:

**Stage 1: Builder**
1. Uses `eclipse-temurin:17-jdk-alpine` (full JDK)
2. Copies Gradle wrapper and build files
3. Downloads dependencies (cached layer)
4. Copies source code
5. Builds fat JAR with `./gradlew clean build -x test`

**Stage 2: Runtime**
1. Uses `eclipse-temurin:17-jre-alpine` (JRE only)
2. Creates non-root user (appuser)
3. Copies fat JAR from builder stage
4. Sets up health check
5. Exposes port 8080

**Why Multi-Stage?**
- Smaller final image (~200MB vs ~800MB)
- No build tools in production image
- Better security (fewer packages)
- Faster deployment (smaller transfer)

---

## Running Containers

### Basic Run

```bash
docker run -d -p 8080:8080 --name skopeo skopeo
```

### With Custom Port Mapping

```bash
docker run -d -p 9000:8080 --name skopeo skopeo
# API available at http://localhost:9000
```

### With JVM Memory Settings

```bash
docker run -d -p 8080:8080 \
  -e JAVA_OPTS="-Xmx1g -Xms512m" \
  --name skopeo \
  skopeo
```

### With Custom Logging

```bash
docker run -d -p 8080:8080 \
  -e JAVA_OPTS="-Dlogback.configurationFile=/app/logback-custom.xml" \
  --name skopeo \
  skopeo
```

### Interactive Mode (See Startup Logs)

```bash
docker run -it -p 8080:8080 --name skopeo skopeo
# Press Ctrl+C to stop
```

### With Volume for Logs (Optional)

```bash
docker run -d -p 8080:8080 \
  -v $(pwd)/logs:/app/logs \
  --name skopeo \
  skopeo
```

---

## Docker Compose

### Configuration

The `docker-compose.yml` file provides a complete service definition:

```yaml
services:
  skopeo:
    build:
      context: .
      dockerfile: Dockerfile
    image: skopeo:latest
    container_name: skopeo
    ports:
      - "8080:8080"
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 3s
      start-period: 10s
      retries: 3
```

### Commands

```bash
# Start in background
docker-compose up -d

# Start in foreground (see logs)
docker-compose up

# Stop the service
docker-compose down

# Rebuild and restart
docker-compose up -d --build

# View logs
docker-compose logs -f

# View logs for last 100 lines
docker-compose logs --tail=100 -f

# Check service status
docker-compose ps

# Check health status
docker-compose ps --format json | jq '.[].Health'
```

---

## Health Checks

### Built-in Health Check

The Docker image includes a health check that queries the `/health` endpoint:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1
```

**Parameters:**
- `--interval=30s`: Check every 30 seconds
- `--timeout=3s`: Fail if check takes > 3 seconds
- `--start-period=10s`: Grace period for startup
- `--retries=3`: Mark unhealthy after 3 failed checks

### Check Container Health

```bash
# View health status
docker inspect --format='{{.State.Health.Status}}' skopeo

# View last 5 health check results
docker inspect --format='{{json .State.Health}}' skopeo | jq

# Watch health status
watch -n 1 'docker inspect --format="{{.State.Health.Status}}" skopeo'
```

**Health States:**
- `starting`: Container is starting (within start-period)
- `healthy`: Health checks passing
- `unhealthy`: Health checks failing (after retries)

### Manual Health Check

```bash
# From host
curl http://localhost:8080/health

# From inside container
docker exec skopeo wget -O- http://localhost:8080/health
```

---

## Logging

### View Logs

```bash
# View all logs
docker logs skopeo

# Follow logs (live tail)
docker logs -f skopeo

# View last 100 lines
docker logs --tail=100 skopeo

# View logs with timestamps
docker logs -t skopeo

# View logs since specific time
docker logs --since=10m skopeo
docker logs --since=2024-01-15T10:00:00 skopeo
```

### Log Configuration

**Default:** Console logging to stdout/stderr (Docker-friendly)

**Log Format:**
```
2024-01-15 10:30:45.123 [main] INFO  org.skopeo.Application - Starting application
```

**To Enable File Logging:**

Create custom logback configuration and mount it:

```bash
docker run -d -p 8080:8080 \
  -v $(pwd)/logback-file.xml:/app/config/logback.xml \
  -e JAVA_OPTS="-Dlogback.configurationFile=/app/config/logback.xml" \
  --name skopeo \
  skopeo
```

---

## Environment Variables

### JAVA_OPTS

Control JVM settings:

```bash
# Memory settings
-e JAVA_OPTS="-Xmx1g -Xms512m"

# Garbage collection
-e JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Debug mode
-e JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# Combined
-e JAVA_OPTS="-Xmx1g -Xms512m -XX:+UseG1GC"
```

### Common Settings

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `JAVA_OPTS` | JVM options | None |
| `PORT` | Application port | 8080 (hardcoded) |

---

## Testing the Deployment

### Automated Testing Script

Use the existing test script:

```bash
# Ensure container is running
docker ps | grep skopeo

# Run API tests
./scripts/test-api.sh
```

### Manual Testing

```bash
# Test root endpoint
curl http://localhost:8080/

# Test health endpoint
curl http://localhost:8080/health

# Test metrics endpoint
curl http://localhost:8080/metrics

# Test ranking calculation
curl -X POST http://localhost:8080/api/v1/calculate-ranking \
  -H "Content-Type: application/json" \
  -d '{
    "players": {
      "P1": {
        "playerId": "player1",
        "name": "John Doe",
        "rating": {"value": 4.5, "system": "NTRP"}
      },
      "P2": {
        "playerId": "player2",
        "name": "Jane Smith",
        "rating": {"value": 4.0, "system": "NTRP"}
      }
    },
    "sets": [
      {"scores": {"P1": 6, "P2": 4}, "winner": "P1"},
      {"scores": {"P1": 6, "P2": 3}, "winner": "P1"}
    ]
  }'
```

---

## Production Deployment

### Best Practices

1. **Use Specific Version Tags**
   ```bash
   docker build -t skopeo:1.0.0 .
   docker run -d -p 8080:8080 skopeo:1.0.0
   ```

2. **Set Resource Limits**
   ```bash
   docker run -d -p 8080:8080 \
     --memory="512m" \
     --cpus="1.0" \
     --name skopeo \
     skopeo:1.0.0
   ```

3. **Use Restart Policy**
   ```bash
   docker run -d -p 8080:8080 \
     --restart=unless-stopped \
     --name skopeo \
     skopeo:1.0.0
   ```

4. **Configure Health Checks**
   - Already built-in
   - Monitor with orchestration tools

5. **Enable Monitoring**
   - Prometheus metrics at `/metrics`
   - Integrate with monitoring stack

6. **Use Read-Only Filesystem (Optional)**
   ```bash
   docker run -d -p 8080:8080 \
     --read-only \
     --tmpfs /tmp \
     --name skopeo \
     skopeo:1.0.0
   ```

### Security Checklist

- ✅ Non-root user (appuser)
- ✅ Minimal base image (Alpine)
- ✅ JRE only (no build tools)
- ✅ No secrets in image
- ✅ Health checks enabled
- ⚠️ Consider: Enable read-only filesystem
- ⚠️ Consider: Use Docker secrets for sensitive data
- ⚠️ Consider: Run behind reverse proxy (nginx, Traefik)

---

## Kubernetes Deployment

### Basic Deployment

Create `k8s/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: skopeo
  labels:
    app: skopeo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: skopeo
  template:
    metadata:
      labels:
        app: skopeo
    spec:
      containers:
      - name: skopeo
        image: skopeo:1.0.0
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: JAVA_OPTS
          value: "-Xmx512m -Xms256m"
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: skopeo
spec:
  selector:
    app: skopeo
  ports:
  - port: 80
    targetPort: 8080
  type: LoadBalancer
```

### Deploy to Kubernetes

```bash
# Apply deployment
kubectl apply -f k8s/deployment.yaml

# Check status
kubectl get pods
kubectl get services

# View logs
kubectl logs -f deployment/skopeo

# Test the service
kubectl port-forward svc/skopeo 8080:80
curl http://localhost:8080/health
```

---

## Troubleshooting

### Container Won't Start

**Check logs:**
```bash
docker logs skopeo
```

**Common issues:**
- Port 8080 already in use
  ```bash
  lsof -i :8080
  docker run -d -p 9000:8080 skopeo  # Use different port
  ```
- Memory issues
  ```bash
  docker run -d -p 8080:8080 -e JAVA_OPTS="-Xmx256m" skopeo
  ```

### Health Check Failing

**Check endpoint manually:**
```bash
docker exec skopeo wget -O- http://localhost:8080/health
```

**Increase start period:**
```bash
# Edit docker-compose.yml
healthcheck:
  start-period: 30s  # Increase from 10s
```

### Image Too Large

**Check image size:**
```bash
docker images | grep skopeo
```

**Optimize:**
- Ensure multi-stage build is used
- Check .dockerignore is in place
- Use `docker image inspect skopeo` to see layer sizes

### Cannot Connect to Container

**Verify container is running:**
```bash
docker ps | grep skopeo
```

**Check port mapping:**
```bash
docker port skopeo
```

**Test from inside container:**
```bash
docker exec skopeo wget -O- http://localhost:8080/health
```

**Check firewall:**
```bash
# macOS
sudo pfctl -sr

# Linux
sudo iptables -L
```

---

## Performance Tuning

### JVM Settings

```bash
# Optimize for container
-e JAVA_OPTS=" \
  -Xmx512m \
  -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200"
```

### Container Resources

```bash
docker run -d -p 8080:8080 \
  --memory="512m" \
  --memory-swap="512m" \
  --cpus="1.0" \
  --name skopeo \
  skopeo
```

---

## Maintenance

### Viewing Container Info

```bash
# Inspect container
docker inspect skopeo

# View resource usage
docker stats skopeo

# View processes
docker top skopeo
```

### Updating the Application

```bash
# Build new version
docker build -t skopeo:1.1.0 .

# Stop old container
docker stop skopeo
docker rm skopeo

# Start new container
docker run -d -p 8080:8080 --name skopeo skopeo:1.1.0
```

### Cleanup

```bash
# Remove stopped containers
docker container prune

# Remove unused images
docker image prune

# Remove everything unused
docker system prune -a
```

---

## Related Documentation

- [API Documentation](../api/API_DOCUMENTATION.md)
- [Testing Strategy](../quality/TESTING_STRATEGY.md)
- [Code Coverage](../quality/CODE_COVERAGE.md)
- [Rating Calculation Algorithm](../../product/RATING_CALCULATION_ALGORITHM.md)

---

## Summary

**Quick Commands:**
```bash
# Build
docker build -t skopeo .

# Run
docker run -d -p 8080:8080 --name skopeo skopeo

# Test
curl http://localhost:8080/health

# Logs
docker logs -f skopeo

# Stop
docker stop skopeo && docker rm skopeo
```

**Or use Docker Compose:**
```bash
docker-compose up -d      # Start
docker-compose logs -f    # Logs
docker-compose down       # Stop
```

---

**Last Updated:** 2024-01-15
