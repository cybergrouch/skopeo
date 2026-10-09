#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# The one home for the app's version arithmetic (#1128), shared by bump-version.yml (a manual major /
# minor / patch bump) and tag-and-ship.yml (the automatic patch bump after a release), so the two can
# never disagree about what "the next version" is. Tested by scripts/next-version.test.sh, run in CI.
#
# The app's version lives in ONE place: `version = "..."` in build.gradle.kts.
#
# Usage:
#   next-version.sh current
#       The version in build.gradle.kts, e.g. 3.2.3-SNAPSHOT.
#
#   next-version.sh next <major|minor|patch> [BASE]
#       The next DEV version: BASE incremented by the given part, with -SNAPSHOT. BASE defaults to the
#       last RELEASED version — the highest vX.Y.Z tag — never the current dev version: 3.2.3-SNAPSHOT
#       means "3.2.3 is coming", so incrementing it would skip 3.2.3. Minor resets patch; major resets
#       minor and patch.
#
#   next-version.sh compare A B
#       -1, 0 or 1 as version A is lower than, equal to or higher than B. A -SNAPSHOT suffix is ignored:
#       3.3.0-SNAPSHOT and 3.3.0 name the same number.
#
# Env: ROOT — the repository to read (default: the git toplevel of the current directory).
#
# Exit codes: 0 ok · 1 usage · 2 unparseable version · 3 no release tag to count from.

set -euo pipefail

ROOT="${ROOT:-$(git rev-parse --show-toplevel)}"
VERSION_RE='^([0-9]+)\.([0-9]+)\.([0-9]+)(-SNAPSHOT)?$'

die() {
  local code="$1"
  shift
  echo "next-version: $*" >&2
  exit "$code"
}

usage() {
  die 1 "usage: next-version.sh current | next <major|minor|patch> [BASE] | compare A B"
}

# Split VERSION into MAJ MIN PAT, or fail with exit 2.
parse() {
  [[ "$1" =~ $VERSION_RE ]] || die 2 "'$1' is not X.Y.Z or X.Y.Z-SNAPSHOT"
  MAJ="${BASH_REMATCH[1]}"
  MIN="${BASH_REMATCH[2]}"
  PAT="${BASH_REMATCH[3]}"
}

current() {
  local line
  line=$(grep -E '^version = "' "$ROOT/build.gradle.kts" | head -1) || die 2 "no 'version = \"...\"' line in build.gradle.kts"
  local value="${line#version = \"}"
  value="${value%\"}"
  parse "$value"
  echo "$value"
}

# The highest vX.Y.Z tag, without the v. Release tags only: anything else (v3.2.0-rc1, a stray tag) is
# ignored rather than allowed to set the base.
last_release() {
  local tag
  tag=$(git -C "$ROOT" tag --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)
  [[ -n "$tag" ]] || die 3 "no vX.Y.Z release tag to count from"
  echo "${tag#v}"
}

next() {
  local part="${1:-}"
  local base="${2:-}"
  [[ -n "$base" ]] || base=$(last_release)
  parse "$base"
  case "$part" in
    major) echo "$((MAJ + 1)).0.0-SNAPSHOT" ;;
    minor) echo "$MAJ.$((MIN + 1)).0-SNAPSHOT" ;;
    patch) echo "$MAJ.$MIN.$((PAT + 1))-SNAPSHOT" ;;
    *) usage ;;
  esac
}

compare() {
  [[ $# -eq 2 ]] || usage
  parse "$1"
  local a=("$MAJ" "$MIN" "$PAT")
  parse "$2"
  local b=("$MAJ" "$MIN" "$PAT")
  for i in 0 1 2; do
    if ((a[i] < b[i])); then
      echo -1
      return
    elif ((a[i] > b[i])); then
      echo 1
      return
    fi
  done
  echo 0
}

case "${1:-}" in
  current) current ;;
  next)
    shift
    next "$@"
    ;;
  compare)
    shift
    compare "$@"
    ;;
  *) usage ;;
esac
