#!/bin/bash
# Code formatting script for Tennis Levelr
# Auto-formats all Kotlin code using ktlint

set -e

echo "======================================"
echo "  🎨 Formatting Code with ktlint"
echo "======================================"
echo ""

# Check if we're in the project root
if [ ! -f "build.gradle.kts" ]; then
    echo "Error: Must be run from project root directory"
    exit 1
fi

echo "Formatting Kotlin code..."
./gradlew ktlintFormat --console=plain

echo ""
echo "======================================"
echo "  ✅ Formatting Complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo "  1. Review changes: git diff"
echo "  2. Verify format: ./gradlew ktlintCheck"
echo "  3. Run tests: ./gradlew test"
echo ""
