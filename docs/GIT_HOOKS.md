# Git Hooks Guide

## Overview

This project uses Git pre-commit hooks to automatically format and verify code style before each commit, ensuring consistent code quality across the team.

---

## Quick Start

### Install the Pre-Commit Hook

```bash
./gradlew installGitHooks
```

That's it! The hook will now run automatically before every commit.

---

## How It Works

When you run `git commit`, the pre-commit hook automatically:

1. **Auto-formats your code** with ktlint
2. **Auto-stages the formatted files**
3. **Runs style verification** with ktlintCheck
4. **Aborts the commit** if unfixable style violations are found

### Example Commit Flow

```bash
$ git add .
$ git commit -m "feat: add new feature"

🎨 Running ktlint format...
✅ Code formatted. Changes auto-staged.
🔍 Checking code style...
✅ Code style check passed!

[main a1b2c3d] feat: add new feature
 5 files changed, 100 insertions(+), 20 deletions(-)
```

---

## Commands

### Install Hook

```bash
./gradlew installGitHooks
```

Installs the pre-commit hook to `.git/hooks/pre-commit`.

**When to run:**
- First time setting up the project
- After cloning the repository
- If the hook gets deleted or corrupted

### Uninstall Hook

```bash
./gradlew uninstallGitHooks
```

Removes the pre-commit hook.

**When to run:**
- If you want to disable automatic formatting
- For troubleshooting
- Before switching to a different hook system

---

## Bypassing the Hook

### Skip for One Commit (Not Recommended)

```bash
git commit --no-verify -m "message"
```

⚠️ **Warning:** This bypasses all formatting and checks. Use only in emergencies.

### When to Skip

- **Never** in normal development
- Only for emergency hotfixes where formatting can be fixed later
- When committing generated code that can't be formatted

---

## What Gets Checked

The pre-commit hook enforces:

- **Indentation**: 4 spaces (no tabs)
- **Line length**: Maximum 120 characters
- **Import ordering**: Alphabetical, grouped
- **Trailing whitespace**: Removed
- **Blank lines**: Consistent spacing
- **Comment formatting**: Proper placement
- **Named parameters**: Required for clarity
- **And more**: All ktlint rules

---

## Troubleshooting

### Hook Not Running

**Problem:** Commits succeed without formatting

**Solution:**
```bash
# Reinstall the hook
./gradlew uninstallGitHooks
./gradlew installGitHooks

# Verify it exists and is executable
ls -l .git/hooks/pre-commit
```

### Hook Fails Every Time

**Problem:** Hook always aborts commits

**Solution:**
```bash
# Run formatter manually to see the issues
./scripts/format-code.sh

# Check for specific violations
./gradlew ktlintCheck

# View detailed report
cat build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt
```

### Slow Hook Execution

**Problem:** Hook takes too long before commits

**Solution:**
The hook runs full formatting and checking, which is necessary for code quality. If it's too slow:

1. Ensure you have a recent Gradle daemon running
2. Consider using `./gradlew --daemon` to keep daemon alive
3. On first run, it may be slower (downloading dependencies)

**Typical timing:**
- First run: ~5-10 seconds (daemon startup)
- Subsequent runs: ~1-3 seconds

### Hook Conflicts with IDE

**Problem:** IDE formats differently than ktlint

**Solution:**
Configure your IDE to use ktlint formatting:

**IntelliJ IDEA / Android Studio:**
1. Install "ktlint" plugin from marketplace
2. Settings → Editor → Code Style → Kotlin
3. Set from → Predefined Style → ktlint

**VS Code:**
1. Install "Kotlin" extension
2. Install "ktlint" extension
3. Set as default formatter

---

## Team Setup

### For New Team Members

When a new developer clones the repository:

```bash
git clone <repository>
cd SkopeoDb
./gradlew installGitHooks  # Install the hook
```

### For Repository Maintainers

Consider adding to `README.md`:

```markdown
## First-Time Setup

After cloning:
```bash
./gradlew installGitHooks
```

This ensures code is automatically formatted before commits.
```

### CI/CD Integration

The pre-commit hook runs locally, but CI should also verify:

```yaml
# .github/workflows/ci.yml
- name: Check code style
  run: ./gradlew ktlintCheck
```

This catches any commits made with `--no-verify`.

---

## Hook Internals

### Hook Location

```
.git/hooks/pre-commit
```

**Note:** This file is not tracked by Git (intentionally).

### Hook Script

The hook script:
1. Runs `./gradlew ktlintFormat --quiet`
2. Checks for file modifications with `git diff`
3. Auto-stages changes with `git add -u`
4. Runs `./gradlew ktlintCheck --quiet`
5. Exits with code 1 if checks fail (aborts commit)

### Customization

To modify the hook behavior, edit the hook generation in `build.gradle.kts`:

```kotlin
tasks.register("installGitHooks") {
    // ... modify hook script here
}
```

Then reinstall:
```bash
./gradlew installGitHooks
```

---

## Best Practices

### ✅ Do

- Install the hook immediately after cloning
- Let the hook auto-format your code
- Review the hook's changes before pushing
- Keep the hook installed during development

### ❌ Don't

- Use `--no-verify` in normal development
- Manually format after the hook runs
- Disable the hook permanently
- Commit unformatted code

---

## FAQ

### Q: Does this slow down commits?

**A:** First run takes ~5 seconds (daemon startup), then ~1-3 seconds per commit. This is worth it for consistent code quality.

### Q: What if I forget to install the hook?

**A:** Your commits will succeed locally, but CI will fail on ktlint checks. Always install the hook after cloning.

### Q: Can I customize the formatting rules?

**A:** ktlint is intentionally opinionated with minimal configuration. This reduces debates about style. See `build.gradle.kts` for available options.

### Q: Does this work on Windows?

**A:** Yes, but ensure you have bash available (Git Bash or WSL). The Gradle tasks work on all platforms.

### Q: What if the hook breaks?

**A:** Uninstall and reinstall:
```bash
./gradlew uninstallGitHooks
./gradlew installGitHooks
```

### Q: Can other developers use different hooks?

**A:** No. The `.git/hooks/pre-commit` file is local and not shared. Each developer must install the hook separately using `./gradlew installGitHooks`.

---

## Related Documentation

- [README.md](../README.md) - Project setup and usage
- [CODE_COVERAGE.md](./CODE_COVERAGE.md) - Code coverage guide
- [TESTING_STRATEGY.md](./TESTING_STRATEGY.md) - Testing approach

---

## Summary

**Installation:**
```bash
./gradlew installGitHooks
```

**Effect:**
- Auto-formats code before every commit
- Ensures style compliance
- Maintains code quality

**Bypass (emergency only):**
```bash
git commit --no-verify
```

---

**Last Updated:** 2024-01-15
