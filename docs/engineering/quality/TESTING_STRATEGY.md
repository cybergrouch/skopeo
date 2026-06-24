# Testing Strategy and Pyramid

## Overview

Skopeo employs a comprehensive testing strategy that balances speed, isolation, and confidence. The testing approach is designed around the principle of **pure functions and dependency injection**, which enables extensive unit testing without mocking.

**Toolchain:** the backend tests are JUnit 5 + Kotest assertions + Ktor `testApplication`, with Testcontainers (PostgreSQL) for the database integration tests. The web client (`web/`) has its own Vitest suite (run via `npm run test:ci`).

**Test counts** drift constantly, so this doc avoids hard-coding a total. As of this writing the backend has 200+ test methods across ~30 test classes, plus the web Vitest suite — for the authoritative current numbers, see the CI "Test Report" / "Web Test Report" checks.

---

## Testing Pyramid

```
           /\
          /  \
         /E2E \        End-to-End Tests (none yet)
        /------\       - Full system tests
       /        \      - External dependencies
      / Integration\   - Slowest, broadest
     /------------\
    /              \   Integration Tests
   / API/HTTP Layer \  - Test HTTP endpoints (Ktor testApplication)
  /------------------\ - JSON serialization, route handling
 /                    \ - DB integration via Testcontainers (PostgreSQL)
/     Unit Tests       \
/----------------------\ Unit Tests (the majority)
                         - Pure function testing
                         - Business logic
                         - Fast, isolated
                         - No dependencies
```

### Distribution

The bulk of the suite is fast, isolated **unit tests** of the pure calculator,
with a smaller layer of **integration tests** covering the HTTP API and (where
applicable) the database. There are **no E2E tests** yet. Exact counts shift as
features land — consult the CI test reports rather than a fixed number here.

| Layer | Speed | Purpose |
|-------|-------|---------|
| Unit | fast | Algorithm correctness, pure logic (no infra) |
| Integration | slower | API contracts, HTTP layer, DB (Testcontainers) |
| E2E | N/A | Not yet implemented |

---

## Test Categories

### 1. Unit Tests

**Location:** `src/test/kotlin/org/skopeo/service/calculator/impl/`

Tests pure business logic in complete isolation - no infrastructure, no HTTP, no JSON. This is the largest part of the suite.

#### 1.1 PerformanceBasedRankingCalculatorImplTest

**File:** `impl/v1/PerformanceBasedRankingCalculatorImplTest.kt`

**Purpose:** Test the core ranking calculation algorithm

Covers, largely via parameterized scenarios (see `TestScenarios.kt`):
- NTRP rating delta calculations
- Boundary enforcement (NTRP 1.0-7.0)
- Dominance, upset, and tiebreak handling
- Rating smoothing (nested suites: NTRP Smoothing, Edge Cases)
- Audit trail content and structure (nested suite: Audit Trail)

#### 1.2 NtrpMatchupMatrixReport

**File:** `impl/v1/NtrpMatchupMatrixReport.kt`

**Purpose:** Generate the full rating-change matrix for match-outcome scenarios (embedded in RATING_CALCULATION_ALGORITHM.md) and verify K-factor scaling

Support files (no tests of their own): `TestScenarios.kt`, `TeamTestHelpers.kt`, `RankingTestCase.kt`.

**Key Characteristics:**
- ✅ No Ktor test application, HTTP layer, or JSON serialization
- ✅ Direct object creation, fast execution
- ✅ Pure function testing without mocking

---

### 2. Integration Tests

**Location:** `src/test/kotlin/org/skopeo/`

Tests the full HTTP API stack including routing, serialization, and validation.
The stateless calculator endpoints boot the application with
`module(initDatabase = false)` (no database required); persistence-layer tests
spin up a real PostgreSQL via **Testcontainers**
(`testsupport/PostgresTestDatabase.kt`), applying the Flyway migration.

Representative classes (not exhaustive — see CI for the full list):

| Class | Purpose |
|-------|---------|
| `SkopeoApplicationTests` | Root, health, and metrics endpoints |
| `OpenAPIIntegrationTest` | `/openapi.yaml` spec and Swagger UI |
| `routes/RankingCalculationApiErrorTest` | API-level success and error responses |
| `routes/*ApiIntegrationTest` | Per-resource route integration (user, rating, match, contact, name, capability) |
| `routes/UserRoutesAuthTest` | Auth-protected route behavior |
| `repository/*RepositoryTest` | Exposed repositories against Testcontainers PostgreSQL |
| `service/calculator/RankingCalculationPayloadTest` | Exact JSON payload snapshot tests |

---

## When to Use Each Test Type

### Use Unit Tests For:

✅ **Business logic**
```kotlin
// Testing calculation correctness
@Test
fun testRatingCalculation() {
    val result = calculator.calculate(request)
    assertEquals(expected, result.response.players["P1"]!!.rating.value)
}
```

✅ **Pure functions**
```kotlin
// Testing determinism
@Test
fun testDeterministic() {
    val result1 = calculator.calculate(request)
    val result2 = calculator.calculate(request)
    assertEquals(result1.response, result2.response)
}
```

✅ **Edge cases**
```kotlin
// Testing boundaries
@Test
fun testMaxRating() {
    val request = createRequest(rating = 7.0, ...)
    val result = calculator.calculate(request)
    assertTrue(result.response.players["P1"]!!.rating.value <= 7.0)
}
```

✅ **Algorithm variations**
```kotlin
// Testing different scenarios
@Test
fun testUpsetWin() { ... }

@Test
fun testDominantWin() { ... }
```

---

### Use Integration Tests For:

✅ **API contracts**
```kotlin
@Test
fun testAPIReturns200() = testApplication {
    val response = client.post("/api/v1/calculate-ranking") { ... }
    assertEquals(HttpStatusCode.OK, response.status)
}
```

✅ **JSON serialization**
```kotlin
@Test
fun testJSONResponse() = testApplication {
    val body = response.bodyAsText()
    assertTrue(body.contains("\"rating\":"))
}
```

✅ **HTTP routing**
```kotlin
@Test
fun testEndpointExists() = testApplication {
    val response = client.post("/api/v1/calculate-ranking") { ... }
    assertNotEquals(HttpStatusCode.NotFound, response.status)
}
```

✅ **Validation at boundaries**
```kotlin
@Test
fun testRejectsInvalidJSON() = testApplication {
    val response = client.post("/api/v1/calculate-ranking") {
        setBody("invalid json")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

---

## Test Organization

### Directory Structure

```
src/test/kotlin/org/skopeo/
├── service/
│   ├── calculator/
│   │   ├── impl/v1/
│   │   │   ├── PerformanceBasedRankingCalculatorImplTest.kt  # Core algorithm + audit suite
│   │   │   ├── NtrpMatchupMatrixReport.kt                    # Rating-change matrix report
│   │   │   ├── TestScenarios.kt                              # Parameterized scenarios
│   │   │   └── TeamTestHelpers.kt                            # Test helpers
│   │   ├── impl/RankingTestCase.kt                           # Test case model
│   │   └── RankingCalculationPayloadTest.kt                  # Payload snapshot tests
│   ├── rating/  contact/  match/  name/  capability/  user/  # Service-layer unit tests
├── repository/*RepositoryTest.kt                            # Exposed repos (Testcontainers PG)
├── routes/*ApiIntegrationTest.kt                            # Per-resource API integration tests
├── routes/RankingCalculationApiErrorTest.kt                # Calculator API error tests
├── routes/UserRoutesAuthTest.kt                            # Auth-protected route tests
├── model/*Test.kt                                          # Domain model validation tests
├── testsupport/                                            # PostgresTestDatabase, TestFirebaseAuth
├── OpenAPIIntegrationTest.kt                               # OpenAPI/Swagger tests
└── SkopeoApplicationTests.kt                               # Application bootstrap tests
```

### Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Test Classes | `*Test.kt` | `RankingCalculationPayloadTest.kt` |
| Test Methods | `test<What>_<Scenario>()` | `testNTRP_BasicMatch_ExactPayload()` |
| Helper Methods | `create*()` or `assert*()` | `createRequest()` |

---

## Testing Best Practices

### 1. Test Pure Functions Without Mocking

**❌ Don't:**
```kotlin
@Test
fun test() {
    val mockLogger = mock<Logger>()
    val calculator = RankingCalculator(mockLogger)
    calculator.calculate(request)
    verify(mockLogger).info("...")
}
```

**✅ Do:**
```kotlin
@Test
fun test() {
    val calculator = PerformanceBasedRankingCalculatorImpl()  // No dependencies!
    val result = calculator.calculate(request)

    // Test the result
    assertEquals(expected, result.response)

    // Test the audit (if needed)
    assertTrue(result.audit.any { it.message.contains("...") })
}
```

### 2. Use Helper Methods

**✅ Good:**
```kotlin
private fun createRequest(
    player1Rating: Double,
    player2Rating: Double,
    sets: List<SetScore>
): RankingCalculationRequest {
    // Build request
}

@Test
fun test() {
    val request = createRequest(4.5, 4.0, sets)
    // Test logic
}
```

### 3. Test One Concept Per Test

**❌ Don't:**
```kotlin
@Test
fun testEverything() {
    // Test NTRP boundaries
    // Test audit trail
    // Test response structure
    // 50 lines of assertions...
}
```

**✅ Do:**
```kotlin
@Test
fun testNTRP_RespectsBoundaries_Max() {
    // Single, focused test
}

@Test
fun testNTRP_RespectsBoundaries_Min() {
    // Single, focused test
}
```

### 4. Use Descriptive Test Names

**✅ Good names:**
- `testNTRP_RespectsBoundaries_Max()`
- `testUpset_UnderdogWins()`
- `testPureFunction_Deterministic()`
- `testAuditTrail_ContainsExpectedScores()`

**❌ Bad names:**
- `test1()`
- `testCalculation()`
- `testIt()`

### 5. Arrange-Act-Assert Pattern

```kotlin
@Test
fun test() {
    // Arrange: Setup test data
    val request = createRequest(...)
    val calculator = PerformanceBasedRankingCalculatorImpl()

    // Act: Execute the behavior
    val result = calculator.calculate(request)

    // Assert: Verify the outcome
    assertEquals(expected, result.response.players["P1"]!!.rating.value)
}
```

---

## Test Coverage Goals

### Where Tests Concentrate

| Area | Test type | Status |
|------|-----------|--------|
| `RankingCalculator` algorithm | Unit | ✅ Heavily covered (parameterized scenarios) |
| Audit trail | Unit (nested suite) | ✅ Covered |
| Service layer (rating, user, match, …) | Unit | ✅ Covered |
| Repositories | Integration (Testcontainers) | ✅ Covered |
| API routes | Integration (`testApplication`) | ✅ Covered |
| Domain models | Unit | ✅ Covered |

### Coverage Targets

The enforced JaCoCo gate is **75% line / 70% branch** over the measured
(service-layer) code; see [CODE_COVERAGE.md](CODE_COVERAGE.md) for what is
measured vs excluded, and the CI coverage summary for current figures.

---

## Architecture Enables Testing

### Pure Function Design

The decision to make `RankingCalculator` a pure function dramatically improves testability:

**Before (Impure):**
```kotlin
class RankingCalculator(private val logger: Logger) {
    fun calculate(request: Request): Response {
        logger.info("...")  // Side effect!
        return response
    }
}

// Testing requires mocking
@Test
fun test() {
    val mockLogger = mock<Logger>()  // Boilerplate!
    val calculator = RankingCalculator(mockLogger)
    // Test logic
    verify(mockLogger)...  // More boilerplate!
}
```

**After (Pure):**
```kotlin
class RankingCalculator {
    fun calculate(request: Request): Result {
        val audit = AuditTrail()  // Collected, not executed
        return Result(response, audit.getEntries())
    }
}

// Testing is trivial
@Test
fun test() {
    val calculator = PerformanceBasedRankingCalculatorImpl()  // No setup!
    val result = calculator.calculate(request)
    // Test logic - clean and simple!
}
```

### Benefits:

1. **No Mocking Required**
   - No mock frameworks needed
   - No verify statements
   - Simpler test code

2. **Fast Execution**
   - No I/O operations
   - No external dependencies
   - Runs in milliseconds

3. **Easy Setup**
   - No test application
   - No HTTP server
   - Just create objects

4. **Comprehensive Coverage**
   - Easy to test edge cases
   - Can test internal logic
   - Can verify audit trail

---

## Running Tests

### All Tests

```bash
./gradlew test
```

### Specific Test Class

```bash
./gradlew test --tests "*.PerformanceBasedRankingCalculatorImplTest"
```

### Specific Test Method

```bash
./gradlew test --tests "*.PerformanceBasedRankingCalculatorImplTest" --tests "*.testNTRP*"
```

### With Coverage Report

```bash
# Coverage report is automatically generated after tests
./gradlew test

# View HTML coverage report
open build/reports/jacoco/test/html/index.html

# Verify coverage meets thresholds
./gradlew jacocoTestCoverageVerification
```

See [CODE_COVERAGE.md](CODE_COVERAGE.md) for detailed coverage documentation.

### Fast Feedback (Unit Tests Only)

```bash
./gradlew test --tests "org.skopeo.service.*"
```

---

## Continuous Integration

### Test Strategy in CI

1. **Every Commit:**
   - Run all unit tests (fast feedback)
   - Fail build if any test fails

2. **Every Pull Request:**
   - Run all tests (unit + integration)
   - Generate coverage report
   - Check coverage thresholds

3. **Before Deployment:**
   - Run all tests
   - Run manual smoke tests
   - Verify test coverage

The actual workflow runs `./gradlew check` (which chains compile → ktlint →
detekt → tests → coverage verification) plus a separate `web` job that runs the
Vitest suite. See [CICD.md](../operations/CICD.md) and `.github/workflows/ci.yml` for the
canonical configuration; a simplified illustration:

```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: |
            17
            21
      - uses: gradle/actions/setup-gradle@v6
      - name: Build, lint, detekt, test, verify coverage
        run: ./gradlew check
```

---

## Test Maintenance

### When to Add Tests

✅ **Always add tests when:**
- Adding new features
- Fixing bugs (test the fix!)
- Refactoring code
- Changing algorithms

### When to Update Tests

✅ **Update tests when:**
- API contracts change
- Validation rules change
- Business logic changes
- Data models change

### When to Remove Tests

✅ **Remove tests when:**
- Feature is removed
- Test duplicates another test
- Test is testing implementation details (not behavior)

---

## Future Testing Enhancements

### Phase 1: Expand Coverage
- [ ] Add performance tests (response time)
- [ ] Add load tests (concurrent requests)
- [ ] Add mutation tests (verify test quality)

### Phase 2: Advanced Testing
- [ ] Property-based testing (hypothesis testing)
- [ ] Fuzz testing (random input generation)
- [ ] Contract testing (API consumer tests)

### Phase 3: E2E Testing
- [ ] Add E2E tests for critical flows
- [ ] Test with real database
- [ ] Test with real external services

---

## Summary

Skopeo uses a **test-first, pure-function-focused** testing strategy:

1. **Unit tests (the majority)** - Fast, isolated, comprehensive
2. **Integration tests** - API contracts and HTTP layer (Ktor `testApplication`), plus repositories against Testcontainers PostgreSQL
3. **No E2E tests** - Not yet needed
4. **Web suite** - the `web/` client has its own Vitest tests (run in the CI `web` job)

The pure function design of `RankingCalculator` enables extensive unit testing without mocking, resulting in:
- Faster test execution
- Simpler test code
- Better coverage
- Easier maintenance

For the authoritative current test counts, see the CI "Test Report" and "Web
Test Report" checks rather than a fixed number here.

---

## Related Documentation

- [AUDIT_TRAIL.md](../architecture/AUDIT_TRAIL.md) - Audit trail design and testing
- [RATING_CALCULATION_ALGORITHM.md](../../product/RATING_CALCULATION_ALGORITHM.md) - Algorithm implementation
- [API_DOCUMENTATION.md](../api/API_DOCUMENTATION.md) - API specifications

---

**Last Updated:** 2024-01-15
