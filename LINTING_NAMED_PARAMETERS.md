# Automated Enforcement of Named Parameters in Kotlin

This document explains how to automate checking for named parameter usage in Kotlin code.

## Current Status

The project has **detekt** configured with the `NamedArguments` rule to enforce named parameters for all function calls (threshold = 1). However, due to Java 26 compatibility issues with the current detekt version, this check is temporarily disabled.

### Configuration

The detekt configuration is in `detekt.yml`:

```yaml
complexity:
  NamedArguments:
    active: true
    threshold: 1  # Enforce named parameters even for single-parameter calls
    ignoreArgumentsMatchingNames: false
```

### Java 26 Compatibility Issue

**Problem**: Detekt 1.23.8 doesn't support Java 26 yet, causing this error:
```
java.lang.IllegalArgumentException: 26.0.1
at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse
```

**Solution**: Once detekt releases a Java 26-compatible version, simply run:
```bash
./gradlew detekt
```

## Alternative Solutions (Available Now)

### 1. IntelliJ IDEA Inspection (Recommended)

IntelliJ IDEA has built-in inspection for this. To enable:

1. Go to **Preferences/Settings** → **Editor** → **Inspections**
2. Navigate to **Kotlin** → **Style issues**
3. Enable **"Function call arguments"**
4. Configure:
   - Check "Flag arguments without name"
   - Set minimum parameters to **1** (to enforce for all calls)
   - Can exclude Java interop methods if needed

#### Run Inspection:
- **Menu**: Code → Inspect Code
- **Shortcut**: ⌥⇧⌘I (Mac) / Alt+Shift+Ctrl+I (Windows/Linux)
- **Scope**: Choose "Whole project" or specific modules

#### Example Output:
```
Function call should use named arguments
Location: TestScenarios.kt:36
   RatingScenario("S1", "Low: Equal players...")
Should be:
   RatingScenario(id = "S1", description = "Low: Equal players...")
```

### 2. Code Review Checklist

Add to your PR review checklist:

- [ ] All Kotlin function calls use named parameters
- [ ] Java interop methods (File, String.format, Math.abs) are excluded
- [ ] Single-parameter calls also use named parameters
- [ ] Constructor calls use named parameters

### 3. Custom ktlint Rule (Advanced)

For teams wanting to enforce this in CI/CD, you can create a custom ktlint rule:

```kotlin
// Custom rule example (requires ktlint rule development)
class NamedParametersRule : Rule("named-parameters") {
    override fun visit(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit
    ) {
        if (node.elementType == ElementType.VALUE_ARGUMENT_LIST) {
            // Check for unnamed arguments
            // Emit error if found
        }
    }
}
```

See [ktlint custom rules documentation](https://ktlint.github.io/) for details.

### 4. Git Pre-commit Hook Enhancement

Once detekt is Java 26-compatible, update `.git/hooks/pre-commit`:

```bash
#!/bin/bash
# Auto-format code before commit

echo "🎨 Running ktlint format..."
./gradlew ktlintFormat --quiet

# Check if formatting introduced any changes
if ! git diff --quiet; then
    echo "✅ Code formatted. Changes auto-staged."
    git add -u
fi

# Verify code style
echo "🔍 Checking code style..."
if ! ./gradlew ktlintCheck --quiet; then
    echo "❌ ktlint check failed. Please fix the issues and try again."
    exit 1
fi

# Run detekt for named parameters and other checks
echo "🔍 Running detekt..."
if ! ./gradlew detekt --quiet; then
    echo "❌ detekt check failed. Please fix the issues and try again."
    exit 1
fi

echo "✅ All checks passed!"
exit 0
```

## Examples

### ✅ Correct Usage

```kotlin
// Single parameter
calculator.calculate(request = myRequest)

// Multiple parameters
RatingScenario(
    id = "S1",
    description = "Low: Equal players, dominant (6-0)",
    ntrpP1 = "2.5",
    ntrpP2 = "2.5",
    // ...
)

// Extension functions
"=".repeat(n = totalWidth)

// Method calls
File("/path").writeText(text = content)
```

### ❌ Incorrect Usage

```kotlin
// Missing named parameters
calculator.calculate(myRequest)

RatingScenario("S1", "Low: Equal players...", "2.5", "2.5")

"=".repeat(totalWidth)
```

### ⚠️ Java Interop Exceptions

These are **correct** (Java methods don't support named parameters):

```kotlin
// Java constructors
File("/tmp/output.txt")

// Java static methods
String.format("%.2f", value)
Math.abs(delta)

// JUnit assertions
assertEquals(expected, actual)
```

## Benefits of Named Parameters

1. **Self-documenting** - Parameter purpose is immediately clear
2. **Refactoring-safe** - Parameter order changes don't break code
3. **Reduced errors** - Can't accidentally swap parameters
4. **Better IDE support** - IntelliJ shows parameter names inline
5. **Code review friendly** - Reviewers understand intent at a glance

## Future: Automated Enforcement

Once detekt supports Java 26:

### Run Locally
```bash
./gradlew detekt
```

### CI/CD Integration
```yaml
# .github/workflows/ci.yml or similar
- name: Run detekt
  run: ./gradlew detekt

- name: Upload detekt report
  uses: actions/upload-artifact@v3
  with:
    name: detekt-report
    path: build/reports/detekt/
```

### Gradle Task
```bash
# Check before commit
./gradlew detekt ktlintCheck test

# Generate report
./gradlew detekt
# Report at: build/reports/detekt/detekt.html
```

## Monitoring Updates

Check for Java 26-compatible detekt versions:
- [Detekt Releases](https://github.com/detekt/detekt/releases)
- [Detekt Compatibility Matrix](https://detekt.dev/docs/introduction/compatibility)

Current version: 1.23.8 (does not support Java 26)
Required: 1.24.0+ (estimated, check releases)

## Summary

**Current Solution**: Use IntelliJ IDEA inspections for immediate feedback during development

**Future Solution**: Automated detekt checks in CI/CD once Java 26 support is available

**Best Practice**: Train team on named parameters and include in code review checklist
