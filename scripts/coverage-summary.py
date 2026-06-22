#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Render the JaCoCo XML report as GitHub-flavored markdown.

Prints an overall line/branch coverage table plus a per-package breakdown to stdout.
In CI the output is appended to $GITHUB_STEP_SUMMARY so coverage shows on the run page
after `./gradlew check`; it also runs locally for a quick read.

Usage: python3 scripts/coverage-summary.py [path-to-jacocoTestReport.xml]
"""
import os
import sys
import xml.etree.ElementTree as ET

# Matches the gate in build.gradle.kts (jacocoTestCoverageVerification).
LINE_THRESHOLD = 75
BRANCH_THRESHOLD = 70

DEFAULT_REPORT = "build/reports/jacoco/test/jacocoTestReport.xml"


def counters(node):
    """type -> (covered, total) for the node's direct <counter> children."""
    result = {}
    for c in node.findall("counter"):
        covered, missed = int(c.get("covered")), int(c.get("missed"))
        result[c.get("type")] = (covered, covered + missed)
    return result


def cell(pair):
    if not pair or pair[1] == 0:
        return "—"
    covered, total = pair
    return f"{100 * covered // total}% ({covered}/{total})"


def badge(pair, threshold):
    if not pair or pair[1] == 0:
        return "➖"
    covered, total = pair
    return "✅" if (100 * covered / total) >= threshold else "❌"


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPORT
    if not os.path.exists(path):
        print("## 📊 Coverage\n\n_No coverage report found — the build likely failed before tests ran._")
        return

    root = ET.parse(path).getroot()
    overall = counters(root)
    line, branch = overall.get("LINE"), overall.get("BRANCH")

    print("## 📊 Coverage\n")
    print("| Metric | Coverage | Threshold | |")
    print("|--------|----------|-----------|--|")
    print(f"| Lines | {cell(line)} | {LINE_THRESHOLD}% | {badge(line, LINE_THRESHOLD)} |")
    print(f"| Branches | {cell(branch)} | {BRANCH_THRESHOLD}% | {badge(branch, BRANCH_THRESHOLD)} |")
    print("\n_Covered classes only; DTOs, models, config, and route wiring are excluded from the gate._\n")

    packages = sorted(root.findall("package"), key=lambda p: p.get("name"))
    if packages:
        print("<details><summary>Per package</summary>\n")
        print("| Package | Lines | Branches |")
        print("|---------|-------|----------|")
        for pkg in packages:
            c = counters(pkg)
            name = pkg.get("name").replace("/", ".")
            print(f"| {name} | {cell(c.get('LINE'))} | {cell(c.get('BRANCH'))} |")
        print("\n</details>")


if __name__ == "__main__":
    main()
