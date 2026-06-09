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

### Coverage by Component

| Component | Line Coverage | Branch Coverage | Status |
|-----------|---------------|-----------------|--------|
| `RankingCalculator` | ~95% | ~90% | ✅ Excellent |
| `AuditTrail` | ~100% | ~100% | ✅ Excellent |
| `RankingRoutes` | ~60% | ~50% | ⚠️ Needs improvement |
| `Application` | ~50% | ~40% | ⚠️ Excluded (infra) |

---

## Exclusions

The following are excluded from coverage metrics:

### Automatically Excluded

1. **Test Code**
   - All code in `src/test/` is excluded
   - Test utilities and helpers

2. **Data Classes** (`**/dto/**`, `**/model/**`)
   - Data transfer objects (DTOs)
   - Model classes (PlayerProfile, Rating, etc.)
   - Reason: Simple data containers, mostly generated code

3. **Application Entry Points** (`**/*Application*.*`)
   - Main application class
   - Servlet initializers
   - Reason: Bootstrap code, hard to test

4. **Kotlin File-Level Functions** (`**/*Kt.class`)
   - Top-level functions in Kotlin files
   - Extension functions
   - Reason: Often simple utilities or generated code

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
        }
    }
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
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Run tests with coverage
        run: ./gradlew test jacocoTestReport

      - name: Verify coverage thresholds
        run: ./gradlew jacocoTestCoverageVerification

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v3
        with:
          files: ./build/reports/jacoco/test/jacocoTestReport.xml
          fail_ci_if_error: true
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

Shows coverage by package:
```
org.skopeo.service     95%  🟢
org.skopeo.routes      60%  🟡
org.skopeo.model       N/A  (excluded)
```

### Class View

Shows coverage by class:
```
RankingCalculator.kt               95%  🟢
AuditTrail.kt                     100%  🟢
RankingRoutes.kt                   60%  🟡
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

- Target: 90%+ for service layer
- Target: 70%+ for routes/controllers
- Data classes can be excluded

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

- [TESTING_STRATEGY.md](./TESTING_STRATEGY.md) - Testing approach
- [API_DOCUMENTATION.md](./API_DOCUMENTATION.md) - API specs
- [RANKING_ALGORITHM.md](./RANKING_ALGORITHM.md) - Algorithm details

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
