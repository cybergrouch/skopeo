#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Tests for scripts/next-version.sh (#1128). Run by CI (ci.yml, build job) and locally:
#   bash scripts/next-version.test.sh
#
# Each case runs the script against a throwaway git repository with its own build.gradle.kts and
# tags, so the table in #1128 is checked against real `git tag` output, not a stub.

set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/next-version.sh"
PASS=0
FAIL=0

# A fresh repo with build.gradle.kts at version $1 and the release tags in $2..; prints its path.
repo() {
  local dir
  dir=$(mktemp -d)
  git -C "$dir" init -q
  git -C "$dir" config user.email t@t
  git -C "$dir" config user.name t
  printf 'plugins {}\nversion = "%s"\n' "$1" >"$dir/build.gradle.kts"
  git -C "$dir" add build.gradle.kts
  git -C "$dir" commit -qm init
  shift
  for tag in "$@"; do git -C "$dir" tag "$tag"; done
  echo "$dir"
}

# expect NAME EXPECTED_OUTPUT EXPECTED_EXIT -- command...
expect() {
  local name="$1" want_out="$2" want_code="$3"
  shift 4
  local out code
  out=$("$@" 2>/dev/null)
  code=$?
  if [[ "$out" == "$want_out" && "$code" == "$want_code" ]]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "FAIL: $name — got '$out' (exit $code), wanted '$want_out' (exit $want_code)"
  fi
}

R=$(repo "3.2.3-SNAPSHOT" v3.1.9 v3.2.2 v3.2.10-rc1 not-a-release)

# current
expect "current reads build.gradle.kts" "3.2.3-SNAPSHOT" 0 -- env ROOT="$R" "$SCRIPT" current

# next, counted from the last RELEASE (v3.2.2): the #1128 table
expect "patch from v3.2.2" "3.2.3-SNAPSHOT" 0 -- env ROOT="$R" "$SCRIPT" next patch
expect "minor from v3.2.2" "3.3.0-SNAPSHOT" 0 -- env ROOT="$R" "$SCRIPT" next minor
expect "major from v3.2.2" "4.0.0-SNAPSHOT" 0 -- env ROOT="$R" "$SCRIPT" next major

# Release tags are compared as versions, not strings, and pre-release tags are ignored.
N=$(repo "3.10.1-SNAPSHOT" v3.9.0 v3.10.0 v3.2.0)
expect "v3.10.0 beats v3.9.0" "3.10.1-SNAPSHOT" 0 -- env ROOT="$N" "$SCRIPT" next patch

# An explicit base: what tag-and-ship passes for the release it is shipping.
expect "patch from an explicit base" "3.2.4-SNAPSHOT" 0 -- env ROOT="$R" "$SCRIPT" next patch 3.2.3

# No release tag at all is refused, not guessed at.
E=$(repo "0.0.1-SNAPSHOT")
expect "no release tag" "" 3 -- env ROOT="$E" "$SCRIPT" next patch

# compare, ignoring -SNAPSHOT
expect "lower" "-1" 0 -- env ROOT="$R" "$SCRIPT" compare 3.2.3-SNAPSHOT 3.3.0-SNAPSHOT
expect "equal across SNAPSHOT" "0" 0 -- env ROOT="$R" "$SCRIPT" compare 3.3.0-SNAPSHOT 3.3.0
expect "higher, numerically" "1" 0 -- env ROOT="$R" "$SCRIPT" compare 3.10.0 3.9.9

# Unparseable input and bad usage fail loudly.
expect "bad part" "" 1 -- env ROOT="$R" "$SCRIPT" next micro
expect "bad version" "" 2 -- env ROOT="$R" "$SCRIPT" compare 3.2 3.2.0
B=$(repo "three-point-two")
expect "bad build.gradle.kts version" "" 2 -- env ROOT="$B" "$SCRIPT" current
expect "no subcommand" "" 1 -- env ROOT="$R" "$SCRIPT"

rm -rf "$R" "$N" "$E" "$B"
echo "next-version.sh: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
