# Audit Trail Documentation

## Overview

The `RankingCalculator` follows a **functional programming pattern** where computation is separated from side effects (logging). Instead of directly logging during calculation, the calculator returns both:
1. The calculation result (`RankingCalculationResponse`)
2. An audit trail (`List<AuditEntry>`)

This approach provides several benefits:
- **Pure Functions**: Calculator has no side effects, making it deterministic and easier to reason about
- **Testability**: Audit information can be tested independently without mocking loggers
- **Flexibility**: Callers can decide how to handle audit information (log it, store it, analyze it, etc.)
- **Separation of Concerns**: Business logic (calculation) is separated from infrastructure (logging)

---

## Design Pattern: Monadic Result

The pattern used is a simplified version of the **Either** or **Writer** monad pattern from functional programming:

```kotlin
// Instead of this (side effects):
fun calculate(request: Request): Response {
    logger.info("Starting calculation")  // Side effect!
    val result = doCalculation()
    logger.info("Calculation complete")  // Side effect!
    return result
}

// We use this (pure function):
fun calculate(request: Request): Result {
    val audit = AuditTrail()
    audit.add(AuditEntry("Starting calculation"))  // Collected, not executed
    val response = doCalculation()
    audit.add(AuditEntry("Calculation complete"))  // Collected, not executed
    return Result(response, audit.getEntries())  // Return both
}
```

---

## Data Structures

### AuditEntry

A single audit entry capturing a point in the calculation. The model is
intentionally minimal — just a human-readable message plus structured context.
There is **no** severity level: entries are not classified as DEBUG/INFO/WARN/ERROR.

```kotlin
data class AuditEntry(
    val message: String,                          // Human-readable message
    val context: Map<String, Any> = emptyMap()    // Structured data (player names, ratings, etc.)
)
```

**Example:**
```kotlin
AuditEntry(
    message = "Calculating ranking for John Doe (4.5) vs Jane Smith (4.0)",
    context = mapOf(
        "player1" to "John Doe",
        "player1Rating" to 4.5,
        "player2" to "Jane Smith",
        "player2Rating" to 4.0
    )
)
```

### RankingCalculationResult

Wraps both the calculation result and audit trail:

```kotlin
data class RankingCalculationResult(
    val response: RankingCalculationResponse,  // The actual calculation result
    val audit: List<AuditEntry>                // The audit trail
)
```

---

## Usage

### In Production Code (RankingRoutes.kt)

The route handler calls the calculator and processes the audit trail:

```kotlin
// Call the pure calculator function
val result = rankingCalculator.calculate(request)

// Process the audit trail (log it)
result.audit.forEach { entry ->
    logger.info { entry.message }
}

// Return the response to the client
call.respond(HttpStatusCode.OK, result.response)
```

### In Tests (PerformanceBasedRankingCalculatorImplTest.kt, Audit Trail suite)

Tests can verify audit information without any logging infrastructure:

```kotlin
@Test
fun testAuditTrailContainsCalculationStart() {
    val request = createSimpleRequest()

    // Call the calculator
    val result = calculator.calculate(request)

    // Verify audit trail contains expected entry
    val startEntry = result.audit.find {
        it.message.contains("Calculating ranking")
    }

    startEntry.shouldNotBeNull()
    startEntry.context["player1"] shouldBe "John Doe"
    startEntry.context["player1Rating"] shouldBe 4.5
}
```

---

## Audit Trail Contents

The `RankingCalculator` produces the following audit entries:

### 1. Calculation Start
```kotlin
AuditEntry(
    message = "Calculating ranking for {player1} ({rating1}) vs {player2} ({rating2})",
    context = {
        "player1": String,
        "player1Rating": Double,
        "player2": String,
        "player2Rating": Double
    }
)
```

### 2. Match Result Analysis
```kotlin
AuditEntry(
    message = "Match result - Winner: {winnerTeamId}, Score: {score}",
    context = {
        "winnerTeamId": String,
        "loserTeamId": String,
        "score": String,                    // per-set score string, e.g. "6-4 6-3"
        "winnerDominanceFactor": BigDecimal,
        "loserDominanceFactor": BigDecimal
    }
)
```

### 3. Adjustment Factors (one entry per player)
Logs every term of the master formula `change = K × dominance × scale × sign`, so the full calculation can be reconstructed from the audit trail alone:

```kotlin
AuditEntry(
    message = "Adjustment factors - {player}: change = K × dominance × scale × sign = {K} × {dominance} × {scale} × {sign} = {change}",
    context = {
        "playerId": String,
        "kFactor": String,                  // 0.160000
        "dominance": String,                // signed per-set average; negative for the loser
        "ratingGap": String,                // |rating1 - rating2|
        "normalizedGap": String,            // ratingGap / ratingRange
        "competitiveThresholdPct": String,  // 0.083000
        "isUpset": String,                  // "true" / "false"
        "upsetMultiplier": String,          // 2.000000
        "scale": String,                    // upset or competitive factor
        "sign": String,                     // "+1" / "-1"
        "change": String                    // the raw change before smoothing/clamping
    }
)
```

### 4. Rating Changes
```kotlin
AuditEntry(
    message = "Rating changes - {player1}: {change1}, {player2}: {change2}",
    context = {
        "player1Change": Double,
        "player2Change": Double
    }
)
```

### 5. NTRP Rating Change
```kotlin
AuditEntry(
    message = "NTRP change: {original} + {change} = {new} -> rounded {rounded} -> clamped {clamped}",
    context = {
        "system": "NTRP",
        "original": Double,
        "change": Double,
        "newValue": Double,
        "rounded": Double,
        "clamped": Double
    }
)
```

---

## Benefits

### 1. Pure Function / No Side Effects

The calculator is a **pure function**:
- Same input always produces same output
- No external state modification
- No I/O operations (logging, database, network)
- Deterministic and predictable

**Benefits:**
- Easier to reason about
- Easier to test
- Can be cached/memoized
- Thread-safe by default
- Can be run in parallel

### 2. Testability Without Mocking

Traditional approach requires mocking:
```kotlin
// BAD: Requires logger mock
@Test
fun testCalculation() {
    val mockLogger = mock<Logger>()
    val calculator = RankingCalculator(mockLogger)

    // Test logic

    // Verify logger was called correctly
    verify(mockLogger).info(contains("Calculating"))
}
```

Our approach tests directly:
```kotlin
// GOOD: No mocking needed
@Test
fun testCalculation() {
    val calculator = RankingCalculator()  // No dependencies!

    val result = calculator.calculate(request)

    // Test both result and audit trail
    assertEquals(expected, result.response)
    assertTrue(result.audit.any { it.message.contains("Calculating") })
}
```

### 3. Flexible Audit Processing

Callers can decide what to do with audit information:

**Log it:**
```kotlin
result.audit.forEach { entry ->
    logger.info { entry.message }
}
```

**Store it in database:**
```kotlin
result.audit.forEach { entry ->
    auditRepository.save(entry)
}
```

**Send to monitoring service:**
```kotlin
result.audit.forEach { entry ->
    metrics.track(entry.message, entry.context)
}
```

**Analyze it:**
```kotlin
val totalEntries = result.audit.size
val mentionsUpset = result.audit.any { it.message.contains("upset") }
```

### 4. Structured Context Data

Each audit entry includes structured context data, not just strings:

```kotlin
val entry = result.audit.first()

// Message is for humans
println(entry.message)  // "Calculating ranking for John Doe (4.5) vs Jane Smith (4.0)"

// Context is for machines
val player1Rating = entry.context["player1Rating"] as Double  // 4.5
val player2Rating = entry.context["player2Rating"] as Double  // 4.0
```

This allows:
- Structured logging (JSON logs)
- Metrics extraction
- Filtering and analysis
- Machine-readable audit trails

---

## Testing Examples

### Test Audit Trail Contains Expected Information

```kotlin
@Test
fun testAuditContainsPlayerNames() {
    val result = calculator.calculate(request)

    val hasJohnDoe = result.audit.any { entry ->
        entry.context["player1"] == "John Doe" ||
        entry.context["player2"] == "John Doe"
    }

    assertTrue(hasJohnDoe)
}
```

### Test Audit Trail Order

```kotlin
@Test
fun testAuditOrder() {
    val result = calculator.calculate(request)
    val messages = result.audit.map { it.message }

    val startIndex = messages.indexOfFirst { it.contains("Calculating") }
    val endIndex = messages.indexOfFirst { it.contains("Rating changes") }

    assertTrue(startIndex < endIndex, "Start should come before end")
}
```

### Test Audit Trail Is Non-Empty

```kotlin
@Test
fun testAuditTrailIsPopulated() {
    val result = calculator.calculate(request)

    result.audit.shouldNotBeEmpty()
    result.audit.any { it.message.contains("Rating changes") } shouldBe true
}
```

### Test Calculation Without Logging

```kotlin
@Test
fun testCalculationProducesCorrectResult() {
    // No logger needed!
    val result = calculator.calculate(request)

    // Test just the calculation result
    assertEquals(expectedRating, result.response.players["P123"]?.rating?.value)

    // Audit trail is separate concern
    assertTrue(result.audit.isNotEmpty())
}
```

---

## Implementation Notes

### AuditTrail Builder

The `AuditTrail` class is a simple collector with a single method:

```kotlin
class AuditTrail {
    private val entries = mutableListOf<AuditEntry>()

    fun add(entry: AuditEntry) {
        entries.add(entry)
    }

    fun getEntries(): List<AuditEntry> = entries.toList()
}
```

**Design Note:** The API is intentionally minimal - there's only one method (`add`). An `AuditEntry` is just a message plus a structured context map, which keeps the API clean and avoids per-level method duplication.

Usage in calculator:
```kotlin
fun calculate(request: Request): Result {
    val audit = AuditTrail()

    audit.add(AuditEntry("Starting", mapOf("request" to request.id)))
    val result = doWork()
    audit.add(AuditEntry("Intermediate", mapOf("value" to result)))

    return Result(response, audit.getEntries())
}
```

**Benefits of single-method API:**
- Simpler interface (one method)
- `AuditEntry` is the primary API surface
- Easy to consume (just iterate and log/store the messages)
- More consistent with functional programming style

### Why Not Just Return Logs?

We could return a list of log strings, but structured entries are better:

**❌ String logs:**
```kotlin
return listOf(
    "INFO: Calculating for John vs Jane",
    "DEBUG: Expected score: 0.5"
)
```

**✅ Structured entries:**
```kotlin
return listOf(
    AuditEntry("Calculating", mapOf("p1" to "John", "p2" to "Jane")),
    AuditEntry("Expected score", mapOf("score" to 0.5))
)
```

Benefits of structured entries:
- Type-safe context data
- Machine-readable
- Can be serialized to JSON
- Supports structured logging

---

## Comparison: Before and After

### Before (With Logger Dependency)

```kotlin
class RankingCalculator(private val logger: Logger) {
    fun calculate(request: Request): Response {
        logger.info("Starting calculation")
        val result = doWork()
        logger.debug("Intermediate value: $result")
        return Response(result)
    }
}

// Test requires mocking
@Test
fun test() {
    val mockLogger = mock<Logger>()
    val calculator = RankingCalculator(mockLogger)
    // ... test logic ...
    verify(mockLogger).info("Starting calculation")
}
```

**Problems:**
- Calculator depends on logger infrastructure
- Tests require mocking
- Can't test audit information easily
- Can't reuse audit information

### After (With Audit Trail)

```kotlin
class RankingCalculator {
    fun calculate(request: Request): Result {
        val audit = AuditTrail()
        audit.add(AuditEntry("Starting calculation"))
        val result = doWork()
        audit.add(AuditEntry("Intermediate value: $result"))
        return Result(Response(result), audit.getEntries())
    }
}

// Test is simple and direct
@Test
fun test() {
    val calculator = RankingCalculator()  // No dependencies!
    val result = calculator.calculate(request)

    assertEquals(expected, result.response)
    assertTrue(result.audit.any { it.message.contains("Starting") })
}
```

**Benefits:**
- Calculator is pure (no dependencies)
- Tests are simple (no mocking)
- Audit information is first-class data
- Flexible processing of audit trail
- Single-method API (`add`) keeps it simple

---

## Future Enhancements

Potential improvements to the audit trail system:

1. **Timestamps**: Add timestamp to each audit entry
2. **Correlation IDs**: Add request ID for distributed tracing
3. **Performance Metrics**: Include duration information
4. **Serialization**: Add JSON serialization for audit entries
5. **Filtering**: Add helper functions to filter audit entries
6. **Audit Analysis**: Add statistics (counts by level, timing analysis)

---

## Related Documentation

- [RATING_CALCULATION_ALGORITHM.md](../../product/RATING_CALCULATION_ALGORITHM.md) - Algorithm implementation details
- [API_DOCUMENTATION.md](../api/API_DOCUMENTATION.md) - API endpoint specifications

---

**Location:** `src/main/kotlin/org/skopeo/service/calculator/`
- `AuditTrail.kt` - `AuditEntry` + audit trail collector
- `RankingCalculationResult.kt` - Result wrapper (response + audit)
- `RankingCalculator.kt` - Pure calculator interface

**Tests:** the audit-trail assertions live in a nested suite inside
`src/test/kotlin/org/skopeo/service/calculator/impl/v1/PerformanceBasedRankingCalculatorImplTest.kt`
