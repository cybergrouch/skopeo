# Scripts Directory

Utility scripts for running and testing the Tennis Levelr API.

## Available Scripts

### 🚀 Server Management

#### `start-server.sh`
Start the Tennis Levelr API server.
- Checks if port 8080 is already in use
- Offers to kill existing process if needed
- Starts the server using Gradle

**Usage:**
```bash
./scripts/start-server.sh
```

#### `stop-server.sh`
Stop the Tennis Levelr API server.
- Finds processes using port 8080
- Safely terminates the server

**Usage:**
```bash
./scripts/stop-server.sh
```

---

### 🧪 Testing

#### `test-api.sh`
Automated test suite for all API endpoints.
- Tests root endpoint
- Tests health endpoint
- Checks response times
- Validates HTTP status codes

**Usage:**
```bash
./scripts/test-api.sh
```

**Sample Output:**
```
🎾 Testing Tennis Levelr API...
================================

✅ Server is running

1️⃣  Testing ROOT endpoint (GET /):
   Response: Tennis Levelr API
   Status: 200
   ✅ PASSED
...
```

---

### 📚 Reference

#### `curl-examples.sh`
Collection of useful cURL commands and examples.
- Basic GET requests
- POST examples for future endpoints
- Testing tips and tricks
- HTTPie alternatives

**Usage:**
```bash
./scripts/curl-examples.sh
```

---

## Quick Start

1. **Make scripts executable:**
   ```bash
   chmod +x scripts/*.sh
   ```

2. **Start the server:**
   ```bash
   ./scripts/start-server.sh
   ```

3. **In a new terminal, test the API:**
   ```bash
   ./scripts/test-api.sh
   ```

4. **When done, stop the server:**
   ```bash
   ./scripts/stop-server.sh
   ```

---

## Manual Testing

Open your browser and navigate to:
- Root: http://localhost:8080/
- Health: http://localhost:8080/health

Or use curl:
```bash
curl http://localhost:8080/health
```

---

## Notes

- All scripts assume the server runs on `http://localhost:8080`
- The `test-api.sh` script will fail if the server is not running
- Use `start-server.sh` to automatically handle port conflicts
