#!/bin/bash
#
# Shared roster of test users, sourced by createTestUsers.sh and deleteTestUsers.sh
# so BOTH operate on the exact same set (deletion is by email, which is fixed here —
# that's why the roster is deterministic rather than randomly generated each run).
#
# Profile details are varied/realistic, and several display names are deliberate
# near-duplicates (John/Jon/Jonathan Smith, Smith/Smyth, Maria/María García,
# Alex/Alexa Johnson) so you can exercise name resolution and search.
#
# Fields, pipe-delimited: email|password|displayName|sex|dateOfBirth
# Edit / extend this list freely; both batch scripts pick up changes automatically.

TEST_USERS=(
  "jon.smith@skopeo.test|Test12345|John Smith|Male|1988-03-14"
  "john.smith@skopeo.test|Test12345|Jon Smith|Male|1991-07-22"
  "jonathan.smith@skopeo.test|Test12345|Jonathan Smith|Male|1979-11-02"
  "jon.smyth@skopeo.test|Test12345|Jon Smyth|Male|1995-01-30"
  "joan.smith@skopeo.test|Test12345|Joan Smith|Female|1984-06-17"
  "maria.garcia@skopeo.test|Test12345|Maria Garcia|Female|1990-09-09"
  "maria.garcia2@skopeo.test|Test12345|María García|Female|1993-12-25"
  "alex.johnson@skopeo.test|Test12345|Alex Johnson|Male|1986-04-05"
  "alexa.johnson@skopeo.test|Test12345|Alexa Johnson|Female|1998-08-19"
  "samuel.lee@skopeo.test|Test12345|Samuel Lee|Male|1982-02-11"
)
