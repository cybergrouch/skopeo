#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later


# =============================================================================
# check-coverage.sh - Run code coverage and verify 85% threshold
# =============================================================================
#
# This script:
# 1. Runs all tests with JaCoCo coverage instrumentation
# 2. Generates coverage reports (HTML, XML, CSV)
# 3. Parses the coverage metrics
# 4. Checks if coverage meets 85% threshold for lines and branches
# 5. Displays detailed coverage breakdown
# 6. Exits with status code 0 if passed, 1 if failed
#
# Usage:
#   ./scripts/check-coverage.sh
#
# Output:
#   - Coverage report at: build/reports/jacoco/test/html/index.html
#   - XML report at: build/reports/jacoco/test/jacocoTestReport.xml
#   - CSV report at: build/reports/jacoco/test/jacocoTestReport.csv
#
# =============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COVERAGE_THRESHOLD=85
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$PROJECT_ROOT/build/reports/jacoco/test"
XML_REPORT="$REPORT_DIR/jacocoTestReport.xml"
HTML_REPORT="$REPORT_DIR/html/index.html"
CSV_REPORT="$REPORT_DIR/jacocoTestReport.csv"

# Print header
echo ""
echo "=========================================="
echo "  Code Coverage Check"
echo "=========================================="
echo ""
echo "Threshold: ${COVERAGE_THRESHOLD}%"
echo "Project: $PROJECT_ROOT"
echo ""

# Step 1: Run tests with coverage
echo -e "${BLUE}Step 1: Running tests with coverage...${NC}"
echo ""

cd "$PROJECT_ROOT"
if ./gradlew clean test jacocoTestReport -x detekt; then
    echo ""
    echo -e "${GREEN}✓ Tests completed successfully${NC}"
else
    echo ""
    echo -e "${RED}✗ Tests failed${NC}"
    exit 1
fi

# Step 2: Check if reports were generated
echo ""
echo -e "${BLUE}Step 2: Checking for coverage reports...${NC}"
echo ""

if [ ! -f "$XML_REPORT" ]; then
    echo -e "${RED}✗ Coverage report not found: $XML_REPORT${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Coverage reports generated${NC}"
echo "  - HTML: $HTML_REPORT"
echo "  - XML:  $XML_REPORT"
echo "  - CSV:  $CSV_REPORT"

# Step 3: Parse coverage metrics from XML report
echo ""
echo -e "${BLUE}Step 3: Parsing coverage metrics...${NC}"
echo ""

# Function to extract coverage percentage from XML
extract_coverage() {
    local type=$1
    local xml_file=$2

    # Extract INSTRUCTION, BRANCH, LINE, etc. coverage
    local missed=$(grep "<counter type=\"$type\"" "$xml_file" | sed -n 's/.*missed="\([0-9]*\)".*/\1/p')
    local covered=$(grep "<counter type=\"$type\"" "$xml_file" | sed -n 's/.*covered="\([0-9]*\)".*/\1/p')

    if [ -z "$missed" ] || [ -z "$covered" ]; then
        echo "0"
        return
    fi

    local total=$((missed + covered))
    if [ $total -eq 0 ]; then
        echo "0"
        return
    fi

    # Calculate percentage (using bc for floating point)
    local percentage=$(echo "scale=2; ($covered * 100) / $total" | bc)
    echo "$percentage"
}

# Extract different coverage metrics
INSTRUCTION_COVERAGE=$(extract_coverage "INSTRUCTION" "$XML_REPORT")
BRANCH_COVERAGE=$(extract_coverage "BRANCH" "$XML_REPORT")
LINE_COVERAGE=$(extract_coverage "LINE" "$XML_REPORT")
COMPLEXITY_COVERAGE=$(extract_coverage "COMPLEXITY" "$XML_REPORT")
METHOD_COVERAGE=$(extract_coverage "METHOD" "$XML_REPORT")
CLASS_COVERAGE=$(extract_coverage "CLASS" "$XML_REPORT")

# Display coverage breakdown
echo "Coverage Breakdown:"
echo "─────────────────────────────────────────"
printf "  %-15s %6.2f%%\n" "Instructions:" "$INSTRUCTION_COVERAGE"
printf "  %-15s %6.2f%%\n" "Branches:" "$BRANCH_COVERAGE"
printf "  %-15s %6.2f%%\n" "Lines:" "$LINE_COVERAGE"
printf "  %-15s %6.2f%%\n" "Complexity:" "$COMPLEXITY_COVERAGE"
printf "  %-15s %6.2f%%\n" "Methods:" "$METHOD_COVERAGE"
printf "  %-15s %6.2f%%\n" "Classes:" "$CLASS_COVERAGE"
echo "─────────────────────────────────────────"

# Step 4: Check if coverage meets threshold
echo ""
echo -e "${BLUE}Step 4: Checking coverage against threshold (${COVERAGE_THRESHOLD}%)...${NC}"
echo ""

PASSED=true

# Check line coverage
LINE_INT=$(echo "$LINE_COVERAGE" | cut -d'.' -f1)
if [ "$LINE_INT" -lt "$COVERAGE_THRESHOLD" ]; then
    echo -e "${RED}✗ Line coverage: ${LINE_COVERAGE}% < ${COVERAGE_THRESHOLD}%${NC}"
    PASSED=false
else
    echo -e "${GREEN}✓ Line coverage: ${LINE_COVERAGE}% ≥ ${COVERAGE_THRESHOLD}%${NC}"
fi

# Check branch coverage
BRANCH_INT=$(echo "$BRANCH_COVERAGE" | cut -d'.' -f1)
if [ "$BRANCH_INT" -lt "$COVERAGE_THRESHOLD" ]; then
    echo -e "${RED}✗ Branch coverage: ${BRANCH_COVERAGE}% < ${COVERAGE_THRESHOLD}%${NC}"
    PASSED=false
else
    echo -e "${GREEN}✓ Branch coverage: ${BRANCH_COVERAGE}% ≥ ${COVERAGE_THRESHOLD}%${NC}"
fi

# Step 5: Display summary and exit
echo ""
echo "=========================================="
echo "  Summary"
echo "=========================================="
echo ""

if [ "$PASSED" = true ]; then
    echo -e "${GREEN}✓ PASSED: Coverage meets the ${COVERAGE_THRESHOLD}% threshold${NC}"
    echo ""
    echo "View detailed report:"
    echo "  open $HTML_REPORT"
    echo ""
    exit 0
else
    echo -e "${RED}✗ FAILED: Coverage below ${COVERAGE_THRESHOLD}% threshold${NC}"
    echo ""
    echo "View detailed report:"
    echo "  open $HTML_REPORT"
    echo ""
    echo "To improve coverage:"
    echo "  1. Add unit tests for uncovered code"
    echo "  2. Add integration tests for API endpoints"
    echo "  3. Add edge case tests for boundary conditions"
    echo ""
    exit 1
fi
