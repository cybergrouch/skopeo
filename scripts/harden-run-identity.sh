#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Give Cloud Run a dedicated, least-privilege service account (#955).
#
# The service runs as the DEFAULT COMPUTE service account, which GCP grants roles/editor at project
# creation. Nobody chose that: Cloud Run falls back to it when no identity is specified. It means the
# running container can delete the Cloud SQL instance, drop Firestore, and rewrite IAM — none of which
# the application has any reason to do. Nothing is exploitable today; the cost is blast radius.
#
# This script performs the SAFE, REVERSIBLE part and stops. It does NOT shift traffic and it does NOT
# remove roles/editor. Both are printed at the end for you to run deliberately.
#
# Usage:
#   ./scripts/harden-run-identity.sh
#
# Config via env:
#   PROJECT    GCP project                        (default: skopeo-prod)
#   REGION     Cloud Run region                   (default: asia-southeast1)
#   SERVICE    Cloud Run service                  (default: skopeo)
#   RUN_SA     runtime account to create/use      (default: skopeo-run@<project>.iam.gserviceaccount.com)
#   DEPLOY_SA  identity CI deploys as; granted actAs on RUN_SA
#                                                 (default: github-deployer@<project>.iam.gserviceaccount.com)
#   TAG        canary revision tag                (default: canary)
#
# What it does, in order:
#   0. Snapshots the project IAM policy to a timestamped file — the backout artifact for everything below.
#   1. Creates RUN_SA if absent and grants it the narrow role set (idempotent).
#   2. Grants DEPLOY_SA actAs on RUN_SA, so deploys keep working once the runtime identity changes.
#   3. Deploys a TAGGED, ZERO-TRAFFIC revision running as RUN_SA, reusing the CURRENT IMAGE DIGEST.
#   4. Curls the canary's own URL and reports whether it came up.
#
# Why the current digest rather than `--source .`: rebuilding would change two variables at once, and a
# deploy that specifies fewer flags than CI does can silently drop configuration. Reusing the digest makes
# the service account the ONLY difference between the canary and what is serving.
#
# Why a tag rather than plain `--no-traffic`: a zero-traffic revision never starts under scale-to-zero, so
# there is nothing to observe. A tag gives the revision its own URL that can be exercised directly while
# no user can reach it.

set -euo pipefail

PROJECT="${PROJECT:-skopeo-prod}"
REGION="${REGION:-asia-southeast1}"
SERVICE="${SERVICE:-skopeo}"
RUN_SA="${RUN_SA:-skopeo-run@${PROJECT}.iam.gserviceaccount.com}"
DEPLOY_SA="${DEPLOY_SA:-github-deployer@${PROJECT}.iam.gserviceaccount.com}"
TAG="${TAG:-canary}"

# Exactly what the application demonstrably uses. Deliberately NOT roles/editor, and deliberately NOT
# roles/cloudbuild.builds.builder: that is a BUILD-time role, and the build is a different identity's job
# (see the closing notes — the compute account keeps it).
RUNTIME_ROLES=(
  roles/cloudsql.client                # Cloud SQL over private IP
  roles/datastore.user                 # Firestore live-score broadcast (#911)
  roles/secretmanager.secretAccessor   # application secrets
  roles/logging.logWriter              # structured logs
  roles/monitoring.metricWriter        # metrics
)

echo "0️⃣  Snapshotting the project IAM policy (the backout artifact)…"
SNAPSHOT="iam-policy-${PROJECT}-$(date +%Y%m%d-%H%M%S).yaml"
gcloud projects get-iam-policy "$PROJECT" --format=yaml >"$SNAPSHOT"
echo "    → $SNAPSHOT"
echo "    restore with: gcloud projects set-iam-policy $PROJECT $SNAPSHOT"

echo "1️⃣  Ensuring ${RUN_SA}…"
if ! gcloud iam service-accounts describe "$RUN_SA" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${RUN_SA%%@*}" \
    --project "$PROJECT" \
    --display-name "Skopeo Cloud Run runtime" \
    --description "Least-privilege runtime identity for the Cloud Run service (#955)"
else
  echo "    already exists"
fi

for role in "${RUNTIME_ROLES[@]}"; do
  echo "    granting ${role}"
  # --condition=None keeps add-iam-policy-binding non-interactive; the call is idempotent.
  gcloud projects add-iam-policy-binding "$PROJECT" \
    --member "serviceAccount:${RUN_SA}" --role "$role" --condition=None >/dev/null
done

echo "2️⃣  Granting ${DEPLOY_SA} actAs on ${RUN_SA}…"
# Without this a deploy cannot launch a revision AS the new identity, and the error names IAM rather
# than this change. Scoped to the one account rather than granted project-wide, which is the narrower
# form of the permission the deployer may already hold.
gcloud iam service-accounts add-iam-policy-binding "$RUN_SA" \
  --project "$PROJECT" \
  --member "serviceAccount:${DEPLOY_SA}" \
  --role roles/iam.serviceAccountUser >/dev/null

echo "3️⃣  Deploying a tagged, zero-traffic canary as ${RUN_SA}…"
CURRENT_REVISION="$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --format='value(status.traffic[0].revisionName)')"
IMAGE="$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --format='value(spec.template.spec.containers[0].image)')"
echo "    serving now: ${CURRENT_REVISION}"
echo "    image:       ${IMAGE}"

gcloud run deploy "$SERVICE" \
  --project "$PROJECT" --region "$REGION" \
  --image "$IMAGE" \
  --service-account "$RUN_SA" \
  --tag "$TAG" --no-traffic

CANARY_URL="$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --format="value(status.traffic.filter(\"tag:${TAG}\").extract(\"url\"))" | tr -d '[]')"
[[ -z "$CANARY_URL" ]] && CANARY_URL="$(gcloud run services describe "$SERVICE" --project "$PROJECT" \
  --region "$REGION" --format='value(status.traffic[].url)' | tr '\t' '\n' | grep -m1 "${TAG}---" || true)"

echo "4️⃣  Probing the canary…"
if [[ -n "$CANARY_URL" ]]; then
  echo "    ${CANARY_URL}/health"
  # The whole point of the exercise: does the app start, reach Cloud SQL and read its secrets as the
  # NEW identity? A 200 with a version string is the evidence; anything else means stop here.
  curl -fsS --max-time 30 "${CANARY_URL}/health" && echo || echo "    ❌ canary did not answer /health"
else
  echo "    ⚠️  could not resolve the canary URL; find it with:"
  echo "        gcloud run services describe $SERVICE --project $PROJECT --region $REGION --format=yaml | grep -A2 'tag: '"
fi

cat <<NEXT

────────────────────────────────────────────────────────────────────────
Nothing serving user traffic has changed. Two steps remain, both manual.

STEP 3 — shift traffic, once the canary answered /health above:

  gcloud run services update-traffic $SERVICE \\
    --project $PROJECT --region $REGION --to-latest

  ROLLBACK (seconds — revisions are immutable, the old one is untouched):
  gcloud run services update-traffic $SERVICE \\
    --project $PROJECT --region $REGION \\
    --to-revisions ${CURRENT_REVISION}=100

STEP 4 — remove roles/editor from the default compute account.
DO THIS IN A SEPARATE CHANGE, and only after at least one successful deploy
has completed on the new identity. That deploy is the test that matters.

  gcloud projects remove-iam-policy-binding $PROJECT \\
    --member serviceAccount:\$(gcloud projects describe $PROJECT \\
      --format='value(projectNumber)')-compute@developer.gserviceaccount.com \\
    --role roles/editor

  Leave roles/cloudbuild.builds.builder in place: Cloud Build runs as that
  account, so it is the BUILD identity and still needs it.

  ROLLBACK: re-add the binding, or restore $SNAPSHOT.
  The failure mode here is silent and delayed — a build breaking an hour
  later pages nobody — so attempt a deploy straight after.
────────────────────────────────────────────────────────────────────────
NEXT
