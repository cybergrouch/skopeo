# Code Coverage Guide

## Overview

Skopeo uses **JaCoCo (Java Code Coverage)** to measure test coverage of production code. Coverage reports are automatically generated after running tests.

**Current Coverage:** ~79% line coverage, ~75% branch coverage

---

## Quick Start

### Generate Coverage Report

```bash
# Run tests (automatically generates coverage report)
./gradlew test

# Open the HTML report
open build/reports/jacoco/test/html/index.html
```

### Verify Coverage Thresholds

```bash
# Check if coverage meets minimum thresholds
./gradlew jacocoTestCoverageVerification

# Run all checks including coverage
./gradlew check
```

---

## Coverage Reports

### HTML Report

**Location:** `build/reports/jacoco/test/html/index.html`

**Features:**
- Interactive web interface
- Line-by-line coverage visualization
- Package and class breakdown
- Sortable columns
- Color-coded coverage levels

**Colors:**
- 🟢 Green: Fully covered
- 🟡 Yellow: Partially covered
- 🔴 Red: Not covered

**To view:**
```bash
# macOS
open build/reports/jacoco/test/html/index.html

# Linux
xdg-open build/reports/jacoco/test/html/index.html

# Windows
start build/reports/jacoco/test/html/index.html
```

### XML Report

**Location:** `build/reports/jacoco/test/jacocoTestReport.xml`

**Purpose:**
- Machine-readable format
- CI/CD integration
- Code coverage services (Codecov, Coveralls, etc.)
- Trend analysis

---

## Coverage Metrics

### Current Thresholds

| Metric | Threshold | Current | Status |
|--------|-----------|---------|--------|
| Line Coverage | 75% | ~79% | ✅ Passing |
| Branch Coverage | 70% | ~75% | ✅ Passing |

### What Is Measured vs Excluded

Coverage is computed only over the **service / business-logic layer**. The route
layer, config wiring, DTOs/models, and the application bootstrap are all excluded
from the JaCoCo report and the verification gate (see [Exclusions](#exclusions)),
so they do **not** contribute a percentage to the measured total.

| Component | In coverage? | Notes |
|-----------|--------------|-------|
| `service/calculator/**` (e.g. `PerformanceBasedRankingCalculatorImpl`) | ✅ Measured | Core algorithm — the bulk of measured coverage |
| `AuditTrail` | ✅ Measured | Exercised by the calculator's audit tests |
| `routes/**` (`RankingRoutes`, `RatingRoutes`, etc.) | ❌ Excluded | Route wiring; happy paths need a Firebase token |
| `config/**` (e.g. `DatabaseConfig`) | ❌ Excluded | Needs a live PostgreSQL instance |
| `dto/**`, `model/**` | ❌ Excluded | Simple data containers |
| `Application` / file-level `*Kt` functions | ❌ Excluded | Bootstrap / generated code |

---

## Exclusions

The following are excluded from coverage metrics:

### Excluded from the Report and the Gate

The exclude patterns below are configured **identically** on both
`jacocoTestReport` and `jacocoTestCoverageVerification` in `build.gradle.kts`:

1. **Test Code**
   - All code in `src/test/` is excluded (it's not production code)

2. **Data Classes** (`**/dto/**`, `**/model/**`)
   - Data transfer objects (DTOs) and model classes (PlayerProfile, Rating, etc.)
   - Reason: Simple data containers, mostly generated code

3. **Application Entry Points** (`**/*Application*.*`)
   - Main application class and Ktor module setup
   - Reason: Bootstrap code, exercised via integration tests

4. **Kotlin File-Level Functions** (`**/*Kt.class`)
   - Top-level functions in Kotlin files
   - Reason: Often simple utilities or generated accessors

5. **Database / Config Wiring** (`**/config/**`)
   - e.g. `DatabaseConfig` — requires a live PostgreSQL instance

6. **Auth & Route Wiring**
   - `**/Security*.*` (auth setup needs a Firebase token)
   - `**/routes/RouteSupport*.*` (shared route helpers)
   - All route classes: `**/routes/UserRoutes*.*`, `**/routes/ContactRoutes*.*`,
     `**/routes/NameRoutes*.*`, `**/routes/CapabilityRoutes*.*`,
     `**/routes/RatingRoutes*.*`, `**/routes/MatchRoutes*.*`,
     `**/routes/RankingRoutes*.*`
   - Reason: Happy paths need a Firebase token (to be covered via the auth
     emulator once provisioning lands). `RankingRoutes*` is auth-free, but its
     handler is a Ktor **suspend route lambda** dispatched inside
     `testApplication`; JaCoCo cannot attribute the exercised coverage back to
     the synthetic lambda class even though `RankingCalculationApiErrorTest`
     asserts its 200 / 400 / 500 behaviour. Excluding it (consistent with every
     other route) removes the misleading uncovered-branch noise.

In addition, the **branch-coverage rule** excludes the route error-handling
lambdas in `*.configureRankingRoutes.*`, since exception-handling branches are
hard to exercise.

> **Note:** Because every `routes/*` class is excluded, route coverage is **not
> measured** — there are no per-route percentages in the report. The measured
> total is essentially the `service/` layer.

### Why Exclude These?

**Data Classes:**
```kotlin
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rating: Rating
)
// No business logic to test - just data storage
```

**Application Bootstrap:**
```kotlin
fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .start(wait = true)
}
// Infrastructure code - tested via integration tests
```

---

## Intentionally-Uncovered Lines (accepted gaps)

A small set of lines inside **measured** files stay red or partially-covered by
design. They are defensive guards, compiler-synthetic paths, or table-DSL
declarations — not real, reachable branches worth a test. They are recorded here
so future coverage triage does not re-litigate them. **Do not chase artificial
coverage for these.**

| Location | What it is | Why it stays uncovered |
|----------|------------|------------------------|
| `service/event/EventService.kt` (`addParticipant`, ~line 305) | `ensureNotNull(events.addParticipant(...))` null arm | **TOCTOU guard.** The event was resolved a few lines earlier; this second null only fires if the row vanishes (concurrent delete) between the two calls. A defensive check, not a reachable path. |
| `service/calculator/AuditTrail.kt` (`AuditEntry`) | Data-class default-arg synthetics (`context` default; synthetic `copy`/constructor path) | Compiler-generated default-argument bytecode. Would be excluded if it lived under `model/`; it earns its place beside the calculator, so the synthetic arm is simply accepted. |
| `service/rating/RatingCalculationService.kt` (`CalculationBreakdown`) | Data-class default-arg synthetics (`sets` default) | Same as `AuditEntry` — synthetic default-argument path, no logic to test. |
| `repository/ClubsTables.kt`, `repository/MatchTables.kt` | Exposed column declarations with `.default(...)` | Table-DSL declaration noise (mirrors the `dto/`/`model/` exclusion rationale). Not executable branch logic. |
| `service/club/ClubService.kt` (`publicByCode` → `sortedByDescending { it.endDate }`, attributed to a phantom `Comparisons.kt`) | Stdlib synthetic comparator | JaCoCo attributes the inlined stdlib nullable-key comparator (its null-handling arm) to a synthetic `Comparisons.kt`. Library code, not app logic. |
| `repository/MatchRepository.kt` (`winLossByUsers`, ~line 375) | `checkNotNull(row[winnerTeamId]).value` — the null/throw arm | The `WHERE winnerTeamId IS NOT NULL` clause already guarantees the value is present, so the throw arm can never fire. Kotlin has no branch-free way to read a nullable column as non-null — `checkNotNull`, `?:`, and `!!` all emit an unreachable arm — so this is inherent, not a missing test. `checkNotNull(...)` is kept because it documents the SQL invariant at the read site. |

The `winLossByUsers` read above deserves a note, since an earlier pass tried to
*eliminate* it rather than accept it. The original `?: return@flatMap emptyList()`
Elvis was rewritten to `checkNotNull(...)` on the theory that the phantom was
gone — but that only **relocated** the unreachable arm (now `checkNotNull`'s
throw path). Because the query filters `winnerTeamId.isNotNull()`, the null arm
is unreachable by construction in *any* form. Making it genuinely coverable would
mean dropping the SQL filter and skipping null-winner rows in memory — trading a
real (if small) query-efficiency cost for a coverage metric. We deliberately keep
the efficient query and accept the phantom instead. **Do not contort the query to
chase this line.**

---

## Understanding Coverage Metrics

### Line Coverage

**Definition:** Percentage of code lines executed by tests

**Example:**
```kotlin
fun calculate(x: Int): Int {
    if (x > 0) {              // Line 1 - Covered ✓
        return x * 2          // Line 2 - Covered ✓
    } else {
        return 0              // Line 3 - Not covered ✗
    }
}

// Line coverage: 66% (2 out of 3 lines)
```

### Branch Coverage

**Definition:** Percentage of decision branches (if/when) executed by tests

**Example:**
```kotlin
fun validate(x: Int): Boolean {
    return x > 0 && x < 100   // Two branches: (x > 0) and (x < 100)
}

// Test with x = 50: Both branches tested ✓
// Test with x = -5: Only first branch tested
// Branch coverage: 100% if both paths tested
```

### Instruction Coverage

**Definition:** Percentage of Java bytecode instructions executed

**Note:** JaCoCo measures at bytecode level, which is more accurate than line counting.

---

## Gradle Tasks

### Test with Coverage

```bash
# Run tests and generate coverage report
./gradlew test

# Coverage report is automatically generated after tests
```

### Coverage Report Only

```bash
# Generate report from existing test results
./gradlew jacocoTestReport
```

### Coverage Verification

```bash
# Check if coverage meets thresholds
./gradlew jacocoTestCoverageVerification

# Returns exit code 0 if passing, 1 if failing
```

### All Checks

```bash
# Run tests, coverage, and all other checks
./gradlew check
```

### Clean and Test

```bash
# Clean previous build and run tests with coverage
./gradlew clean test
```

---

## Configuration

### Coverage Thresholds

**Location:** `build.gradle.kts`

```kotlin
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.75".toBigDecimal() // 75% line coverage
            }
        }

        rule {
            element = "CLASS"
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal() // 70% branch coverage
            }

            // Route error-handling lambdas are hard to exercise
            excludes = listOf("*.configureRankingRoutes.*")
        }
    }

    // Same class-directory exclusions as the report (dto/model/config/routes/
    // Security/Application/*Kt) — see the Exclusions section above.
}
```

### Adjusting Thresholds

**To increase quality bar:**
```kotlin
minimum = "0.85".toBigDecimal() // 85% coverage required
```

**To temporarily lower (not recommended):**
```kotlin
minimum = "0.60".toBigDecimal() // 60% coverage required
```

### Adding Exclusions

```kotlin
classDirectories.setFrom(
    files(classDirectories.files.map {
        fileTree(it) {
            exclude(
                "**/dto/**",           // Existing
                "**/model/**",         // Existing
                "**/*Application*.*",  // Existing
                "**/config/**",        // Add new exclusion
                "**/*Constants*.*"     // Add new exclusion
            )
        }
    })
)
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Tests with Coverage

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - name: Set up JDK
        uses: actions/setup-java@v5
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Run tests with coverage
        run: ./gradlew test jacocoTestReport

      - name: Verify coverage thresholds
        run: ./gradlew jacocoTestCoverageVerification

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v7
        with:
          files: ./build/reports/jacoco/test/jacocoTestReport.xml
          fail_ci_if_error: false
```

### Jenkins Example

```groovy
stage('Test with Coverage') {
    steps {
        sh './gradlew clean test jacocoTestReport'

        publishHTML([
            reportDir: 'build/reports/jacoco/test/html',
            reportFiles: 'index.html',
            reportName: 'Code Coverage Report'
        ])

        sh './gradlew jacocoTestCoverageVerification'
    }
}
```

---

## Interpreting the Report

### Overall Summary

```
Element         | Missed | Covered | Total  | Coverage
----------------|--------|---------|--------|----------
Instructions    | 234    | 891     | 1,125  | 79%
Branches        | 45     | 135     | 180    | 75%
Lines           | 123    | 456     | 579    | 79%
Methods         | 12     | 89      | 101    | 88%
Classes         | 2      | 18      | 20     | 90%
```

**Focus on:**
- **Instructions** - Most accurate metric
- **Branches** - Tests decision logic
- **Lines** - Easy to understand

### Package View

Shows coverage by package (excluded packages don't appear in the report):
```
org.skopeo.service     measured  🟢
org.skopeo.routes      N/A  (excluded)
org.skopeo.config      N/A  (excluded)
org.skopeo.model       N/A  (excluded)
```

### Class View

Shows coverage by class (only measured classes appear):
```
PerformanceBasedRankingCalculatorImpl.kt   measured  🟢
AuditTrail.kt                              measured  🟢
RankingRoutes.kt                           N/A  (excluded)
```

### Line View

Color-coded source code:
- **Green background** - Line executed by tests
- **Red background** - Line not executed
- **Yellow background** - Branch partially covered
- **Red diamond** - Branch not taken
- **Green diamond** - Branch taken

---

## Improving Coverage

### 1. Identify Uncovered Code

```bash
# Generate report
./gradlew test

# Open report
open build/reports/jacoco/test/html/index.html

# Navigate to specific class
# Red lines = not covered
```

### 2. Write Tests for Uncovered Lines

**Example:**
```kotlin
// Uncovered branch
fun validate(x: Int): Boolean {
    if (x < 0) return false  // Covered ✓
    if (x > 100) return false  // Not covered ✗
    return true
}

// Add test for uncovered branch
@Test
fun testValidate_AboveMax() {
    assertFalse(validate(101))  // Now covers the x > 100 branch
}
```

### 3. Focus on Business Logic

**Priority:**
1. Core algorithms (RankingCalculator)
2. Business rules
3. Validation logic
4. Error handling

**Lower priority:**
1. Data classes
2. Configuration
3. Infrastructure code

### 4. Avoid Testing for Coverage

**❌ Don't:**
```kotlin
@Test
fun testEveryGetterAndSetter() {
    // Testing generated code just for coverage
}
```

**✅ Do:**
```kotlin
@Test
fun testBusinessLogic() {
    // Testing actual behavior
}
```

---

## Common Issues

### Issue: Coverage Too Low

**Symptoms:**
```
Rule violated for bundle: instructions covered ratio is 0.65, but expected minimum is 0.75
```

**Solutions:**
1. Write more unit tests
2. Add missing integration tests
3. Lower threshold temporarily
4. Exclude more generated code

### Issue: False Coverage

**Problem:** Tests execute code but don't verify behavior

**Example:**
```kotlin
@Test
fun test() {
    calculator.calculate(request)  // Executes code
    // No assertions! ❌
}
```

**Solution:** Always assert outcomes
```kotlin
@Test
fun test() {
    val result = calculator.calculate(request)
    assertEquals(expected, result.response)  // Verify behavior ✓
}
```

### Issue: Flaky Coverage

**Problem:** Coverage changes between runs

**Cause:** Tests running in random order or parallel

**Solution:** Ensure tests are independent

---

## Best Practices

### 1. Check Coverage Locally Before Committing

```bash
./gradlew clean test
open build/reports/jacoco/test/html/index.html
```

### 2. Maintain High Coverage for Business Logic

- Target: 90%+ for the service layer (the only measured layer)
- Routes, config, DTOs, and the app bootstrap are excluded (verified via
  integration tests instead)

### 3. Don't Game the System

**❌ Bad:**
```kotlin
@Test
fun increaseCoverage() {
    // Call methods without assertions
}
```

**✅ Good:**
```kotlin
@Test
fun testBusinessBehavior() {
    // Test actual requirements
    assertEquals(expected, actual)
}
```

### 4. Review Coverage in PRs

- Check coverage report before merging
- Don't merge PRs that significantly decrease coverage
- Discuss coverage impact in code reviews

### 5. Track Coverage Trends

- Monitor coverage over time
- Aim for steady improvement
- Investigate sudden drops

---

## Tools Integration

### IntelliJ IDEA

**Run with Coverage:**
1. Right-click on test class
2. Select "Run with Coverage"
3. View inline coverage in editor

**Keyboard Shortcut:**
- macOS: `Control + Shift + R`
- Windows/Linux: `Ctrl + Shift + F10`

### VS Code

**Extensions:**
- Coverage Gutters
- JaCoCo Coverage Viewer

**View Coverage:**
1. Install extension
2. Run `./gradlew test`
3. Extension shows coverage inline

---

## Related Documentation

- [TESTING_STRATEGY.md](TESTING_STRATEGY.md) - Testing approach
- [API_DOCUMENTATION.md](../api/API_DOCUMENTATION.md) - API specs
- [RATING_CALCULATION_ALGORITHM.md](../../product/RATING_CALCULATION_ALGORITHM.md) - Algorithm details

---

## Summary

**Coverage Plugin:** JaCoCo 0.8.12
**Current Coverage:** ~79% lines, ~75% branches
**Minimum Thresholds:** 75% lines, 70% branches
**Report Location:** `build/reports/jacoco/test/html/index.html`

**Key Commands:**
```bash
./gradlew test                           # Run tests + generate report
./gradlew jacocoTestCoverageVerification # Verify thresholds
open build/reports/jacoco/test/html/index.html  # View report
```

---

**Last Updated:** 2024-01-15
