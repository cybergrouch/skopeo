# Testing Strategy and Pyramid

## Overview

Skopeo employs a comprehensive testing strategy that balances speed, isolation, and confidence. The testing approach is designed around the principle of **pure functions and dependency injection**, which enables extensive unit testing without mocking.

---

## Testing Pyramid

```
           /\
          /  \
         /E2E \        End-to-End Tests (0)
        /------\       - Full system tests
       /        \      - External dependencies
      / Integration\   - Slowest, broadest
     /------------\
    /              \   Integration Tests (16 tests)
   / API/HTTP Layer \  - Test HTTP endpoints
  /------------------\ - JSON serialization
 /                    \ - Route handling
/     Unit Tests       \
/----------------------\ Unit Tests (107 tests)
                         - Pure function testing
                         - Business logic
                         - Fast, isolated
                         - No dependencies
```

### Distribution

| Layer | Tests | Percentage | Avg Speed | Purpose |
|-------|-------|------------|-----------|---------|
| Unit | 107 | 87% | fast | Algorithm correctness, pure logic |
| Integration | 16 | 13% | slower | API contracts, HTTP layer |
| E2E | 0 | 0% | N/A | Not yet implemented |
| **Total** | **123** | **100%** | ~6s | Full coverage |

---

## Test Categories

### 1. Unit Tests (107 tests)

**Location:** `src/test/kotlin/org/skopeo/service/calculator/impl/`

Tests pure business logic in complete isolation - no infrastructure, no HTTP, no JSON.

#### 1.1 PerformanceBasedRankingCalculatorImplTest (106 tests)

**File:** `impl/v1/PerformanceBasedRankingCalculatorImplTest.kt`

**Purpose:** Test the core ranking calculation algorithm

Covers, largely via parameterized scenarios (see `TestScenarios.kt`):
- NTRP and UTR rating delta calculations
- Boundary enforcement (NTRP 1.0-7.0, UTR >= 1.0)
- Dominance, upset, and tiebreak handling
- Rating smoothing (nested suites: NTRP Smoothing, UTR Smoothing, Edge Cases)
- Audit trail content and structure (nested suite: Audit Trail)

#### 1.2 RatingChangeReport (1 test)

**File:** `impl/v1/RatingChangeReport.kt`

**Purpose:** Generate the full rating-change table for all match-outcome scenarios in both NTRP and UTR (embedded in RATING_CALCULATION_ALGORITHM.md) and verify the 2.5× K-factor scaling

Support files (no tests of their own): `TestScenarios.kt`, `TeamTestHelpers.kt`, `RankingTestCase.kt`.

**Key Characteristics:**
- ✅ No Ktor test application, HTTP layer, or JSON serialization
- ✅ Direct object creation, fast execution
- ✅ Pure function testing without mocking

---

### 2. Integration Tests (16 tests)

**Location:** `src/test/kotlin/org/skopeo/`

Tests the full HTTP API stack including routing, serialization, and validation.
All boot the application with `module(initDatabase = false)` - no database required.

| Class | Tests | Purpose |
|-------|-------|---------|
| `SkopeoApplicationTests` | 3 | Root, health, and metrics endpoints |
| `OpenAPIIntegrationTest` | 2 | `/openapi.yaml` spec and Swagger UI |
| `routes/RankingCalculationApiErrorTest` | 4 | API-level success and error responses |
| `service/calculator/RankingCalculationPayloadTest` | 7 | Exact JSON payload snapshot tests |

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
├── service/calculator/
│   ├── impl/v1/
│   │   ├── PerformanceBasedRankingCalculatorImplTest.kt  # Core algorithm tests
│   │   ├── RatingChangeReport.kt                         # Rating-change table for both systems
│   │   ├── TestScenarios.kt                              # Parameterized scenarios
│   │   └── TeamTestHelpers.kt                            # Test helpers
│   ├── impl/RankingTestCase.kt                           # Test case model
│   └── RankingCalculationPayloadTest.kt                  # Payload snapshot tests
├── routes/RankingCalculationApiErrorTest.kt              # API integration tests
├── OpenAPIIntegrationTest.kt                             # OpenAPI/Swagger tests
└── SkopeoApplicationTests.kt                             # Application tests
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
    system: RatingSystem,
    sets: List<SetScore>
): RankingCalculationRequest {
    // Build request
}

@Test
fun test() {
    val request = createRequest(4.5, 4.0, NTRP, sets)
    // Test logic
}
```

### 3. Test One Concept Per Test

**❌ Don't:**
```kotlin
@Test
fun testEverything() {
    // Test NTRP boundaries
    // Test UTR boundaries
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

### Current Coverage

| Component | Unit Tests | Integration Tests | Total | Status |
|-----------|------------|-------------------|-------|--------|
| RankingCalculator | 26 | 9 | 35 | ✅ Excellent |
| AuditTrail | 14 | 0 | 14 | ✅ Excellent |
| API Routes | 0 | 21 | 21 | ✅ Good |
| Data Models | 0 | 30 | 30 | ✅ Good (via integration) |

### Coverage Targets

| Metric | Target | Current | Status |
|--------|--------|---------|--------|
| Line Coverage | 80%+ | ~85% | ✅ |
| Branch Coverage | 75%+ | ~80% | ✅ |
| Unit Tests | 50%+ | 57% | ✅ |
| Integration Tests | 30-50% | 43% | ✅ |

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

See [CODE_COVERAGE.md](./CODE_COVERAGE.md) for detailed coverage documentation.

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

### GitHub Actions Example

```yaml
name: Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run tests
        run: ./gradlew test
      - name: Generate coverage
        run: ./gradlew jacocoTestReport
      - name: Upload coverage
        uses: codecov/codecov-action@v2
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

1. **57% Unit Tests** - Fast, isolated, comprehensive
2. **43% Integration Tests** - API contracts, HTTP layer
3. **0% E2E Tests** - Not yet needed

The pure function design of `RankingCalculator` enables extensive unit testing without mocking, resulting in:
- Faster test execution
- Simpler test code
- Better coverage
- Easier maintenance

Total: **123 tests** providing confidence in both business logic and API contracts.

---

## Related Documentation

- [AUDIT_TRAIL.md](./AUDIT_TRAIL.md) - Audit trail design and testing
- [RATING_CALCULATION_ALGORITHM.md](./RATING_CALCULATION_ALGORITHM.md) - Algorithm implementation
- [API_DOCUMENTATION.md](./API_DOCUMENTATION.md) - API specifications

---

**Last Updated:** 2024-01-15
