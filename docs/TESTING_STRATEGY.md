# Testing Strategy and Pyramid

## Overview

Tennis Levelr employs a comprehensive testing strategy that balances speed, isolation, and confidence. The testing approach is designed around the principle of **pure functions and dependency injection**, which enables extensive unit testing without mocking.

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
    /              \   Integration Tests (30 tests)
   / API/HTTP Layer \  - Test HTTP endpoints
  /------------------\ - JSON serialization
 /                    \ - Route handling
/     Unit Tests       \
/----------------------\ Unit Tests (40 tests)
                         - Pure function testing
                         - Business logic
                         - Fast, isolated
                         - No dependencies
```

### Distribution

| Layer | Tests | Percentage | Avg Speed | Purpose |
|-------|-------|------------|-----------|---------|
| Unit | 40 | 57% | ~500ms | Algorithm correctness, pure logic |
| Integration | 30 | 43% | ~5000ms | API contracts, HTTP layer |
| E2E | 0 | 0% | N/A | Not yet implemented |
| **Total** | **70** | **100%** | **~5500ms** | Full coverage |

---

## Test Categories

### 1. Unit Tests (40 tests)

**Location:** `src/test/kotlin/org/lange/tennis/levelr/service/`

Tests pure business logic in complete isolation - no infrastructure, no HTTP, no JSON.

#### 1.1 RankingCalculatorUnitTest (26 tests)

**File:** `RankingCalculatorUnitTest.kt`

**Purpose:** Test the core ranking calculation algorithm

**Tests:**
- NTRP-specific behavior (4 tests)
  - Boundary enforcement (1.0-7.0)
  - Rounding to 2 decimal places
  - Rating gain/loss mechanics

- UTR-specific behavior (4 tests)
  - Minimum enforcement (1.0+)
  - No maximum limit
  - Rounding to 1 decimal place
  - Rating gain/loss mechanics

- Dominance factor (2 tests)
  - Dominant vs close wins
  - Multi-set match handling

- Elo expected scores (2 tests)
  - Higher rated player advantage
  - Equal ratings = 50/50

- Upset scenarios (2 tests)
  - Underdog victories
  - Expected outcomes

- Pure function properties (2 tests)
  - Determinism (same input = same output)
  - No side effects (immutability)

- Audit trail integration (2 tests)
  - Audit completeness
  - Structured context data

- Response structure (3 tests)
  - Player data correctness
  - Rating change calculations
  - Percent change accuracy

**Example:**
```kotlin
@Test
fun testNTRP_RespectsBoundaries_Max() {
    val calculator = RankingCalculator()  // No dependencies!

    val request = createRequest(
        player1Rating = 7.0,  // At max
        player2Rating = 6.5,
        system = RatingSystem.NTRP,
        sets = listOf(SetScore(mapOf("P1" to 6, "P2" to 0), "P1"))
    )

    val result = calculator.calculate(request)

    assertTrue(result.response.players["P1"]!!.rating.value <= 7.0)
}
```

**Key Characteristics:**
- ✅ No Ktor test application
- ✅ No HTTP layer
- ✅ No JSON serialization
- ✅ Direct object creation
- ✅ Fast execution (~500ms for all 26 tests)
- ✅ Easy to write and maintain

---

#### 1.2 RankingCalculatorAuditTest (10 tests)

**File:** `RankingCalculatorAuditTest.kt`

**Purpose:** Verify audit trail functionality

**Tests:**
- Audit content verification (4 tests)
  - Calculation start message
  - Match result analysis
  - Expected score calculations
  - Rating change details

- Audit structure (3 tests)
  - Entry ordering
  - Level distribution (INFO vs DEBUG)
  - Context data validity

- System-specific audits (2 tests)
  - NTRP change tracking
  - UTR change tracking

- Audit metadata (1 test)
  - Zero-sum rating changes

**Example:**
```kotlin
@Test
fun testAuditTrailContainsExpectedScores() {
    val result = calculator.calculate(request)

    val expectedEntry = result.audit.find {
        it.message.contains("Expected scores")
    }

    assertNotNull(expectedEntry)
    assertEquals(0.5, expectedEntry!!.context["expectedPlayer1"], 0.01)
}
```

**Key Benefits:**
- Tests audit trail without logging infrastructure
- Verifies structured context data
- Ensures audit completeness
- Tests ordering and levels

---

#### 1.3 AuditTrailSimpleExample (4 tests)

**File:** `AuditTrailSimpleExample.kt`

**Purpose:** Demonstrate and validate the AuditTrail API

**Tests:**
- Simple API usage
- AuditEntry as data
- Filtering by level
- Context data access

**Example:**
```kotlin
@Test
fun demonstrateSimpleAPI() {
    val audit = AuditTrail()

    audit.add(AuditEntry(INFO, "Starting"))
    audit.add(AuditEntry(DEBUG, "Step 1", mapOf("value" to 42)))

    val entries = audit.getEntries()
    assertEquals(2, entries.size)
}
```

---

### 2. Integration Tests (30 tests)

**Location:** `src/test/kotlin/org/lange/tennis/levelr/`

Tests the full HTTP API stack including routing, serialization, and validation.

#### 2.1 RankingCalculationApiTest (10 tests)

**File:** `RankingCalculationApiTest.kt`

**Purpose:** Test core API functionality

**Tests:**
- Valid requests (2 tests)
  - Simple NTRP match
  - UTR match with tiebreak

- Invalid ratings (2 tests)
  - Out of range values
  - Continuous values (now valid)

- Data integrity (3 tests)
  - Mismatched player IDs
  - Different rating systems
  - Invalid set scores

**Example:**
```kotlin
@Test
fun testValidRankingCalculation() = testApplication {
    application { module() }

    val response = client.post("/api/v1/calculate-ranking") {
        contentType(ContentType.Application.Json)
        setBody("""{ ... }""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
}
```

**Key Characteristics:**
- ✅ Tests full HTTP stack
- ✅ Tests JSON serialization/deserialization
- ✅ Tests Ktor routing
- ✅ Tests validation at API boundary
- ⚠️ Slower than unit tests

---

#### 2.2 RankingCalculationExtendedTest (11 tests)

**File:** `RankingCalculationExtendedTest.kt`

**Purpose:** Extended edge case testing

**Tests:**
- Multi-set matches (1 test)
- Boundary ratings (3 tests)
  - Minimum NTRP/UTR
  - Maximum NTRP
  - High UTR values

- Validation edge cases (5 tests)
  - Empty player names
  - Empty player IDs
  - Too many/few players
  - Invalid winners

- Optional features (1 test)
  - Match date handling

---

#### 2.3 RankingAlgorithmTest (9 tests)

**File:** `RankingAlgorithmTest.kt`

**Purpose:** Algorithm behavior through HTTP API

**Tests:**
- Rating changes (2 tests)
  - NTRP winner gains rating
  - UTR winner gains rating

- Upsets (1 test)
  - Lower-rated player wins

- Match dominance (2 tests)
  - Dominant vs close wins

- Boundaries (3 tests)
  - NTRP max/min enforcement
  - UTR minimum enforcement

**Note:** These tests duplicate some unit test coverage but verify the full stack works end-to-end.

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
src/test/kotlin/org/lange/tennis/levelr/
├── service/                              # Unit tests
│   ├── RankingCalculatorUnitTest.kt     # Core algorithm tests
│   ├── RankingCalculatorAuditTest.kt    # Audit trail tests
│   └── AuditTrailSimpleExample.kt       # API examples
├── RankingCalculationApiTest.kt         # Integration tests
├── RankingCalculationExtendedTest.kt    # Extended integration
├── RankingAlgorithmTest.kt              # Algorithm integration
└── TennisLevelrApplicationTests.kt      # Application tests
```

### Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Unit Tests | `*UnitTest.kt` | `RankingCalculatorUnitTest.kt` |
| Integration Tests | `*ApiTest.kt` or `*Test.kt` | `RankingCalculationApiTest.kt` |
| Test Methods | `test<What><Scenario>()` | `testNTRP_RespectsBoundaries_Max()` |
| Helper Methods | `create*()` or `assert*()` | `createRequest()`, `assertErrorResponse()` |

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
    val calculator = RankingCalculator()  // No dependencies!
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
    val calculator = RankingCalculator()

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
    val calculator = RankingCalculator()  // No setup!
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
./gradlew test --tests "RankingCalculatorUnitTest"
```

### Specific Test Method

```bash
./gradlew test --tests "RankingCalculatorUnitTest.testNTRP_RespectsBoundaries_Max"
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
./gradlew test --tests "org.lange.tennis.levelr.service.*"
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

Tennis Levelr uses a **test-first, pure-function-focused** testing strategy:

1. **57% Unit Tests** - Fast, isolated, comprehensive
2. **43% Integration Tests** - API contracts, HTTP layer
3. **0% E2E Tests** - Not yet needed

The pure function design of `RankingCalculator` enables extensive unit testing without mocking, resulting in:
- Faster test execution
- Simpler test code
- Better coverage
- Easier maintenance

Total: **70 tests** providing confidence in both business logic and API contracts.

---

## Related Documentation

- [AUDIT_TRAIL.md](./AUDIT_TRAIL.md) - Audit trail design and testing
- [RANKING_ALGORITHM.md](./RANKING_ALGORITHM.md) - Algorithm implementation
- [API_DOCUMENTATION.md](./API_DOCUMENTATION.md) - API specifications

---

**Last Updated:** 2024-01-15
