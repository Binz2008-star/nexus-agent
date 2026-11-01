#!/usr/bin/env bash
# One-command Cloud Run deployment for the NEXUS Neon backend (staging/prod) + rollback + dry-run.
#
# SECURITY / ISOLATION:
#   - staging and prod use SEPARATE service names, service accounts, and Secret Manager secrets
#     (env-scoped via a `-staging` suffix). Staging can NEVER read production Neon credentials.
#   - ENV is validated fail-closed (must be exactly `staging` or `prod`).
#   - The Docker image path is unified on Artifact Registry for build/deploy/rollback.
#   - Rollback targets the latest READY (healthy) revision, not merely the second-newest.
#
# BILLING: `deploy` is billable + traffic-affecting. Only run after explicit owner authorization.
# `validate` and `dry-run` create no billable resources.
#
# Usage:
#   ENV=staging PROJECT_ID=<gcp> ./deploy.sh validate
#   ENV=staging PROJECT_ID=<gcp> ./deploy.sh build <tag>
#   ENV=staging PROJECT_ID=<gcp> ./deploy.sh dry-run <tag>
#   ENV=staging PROJECT_ID=<gcp> ./deploy.sh deploy <tag>     # BILLABLE, confirms
#   ENV=staging PROJECT_ID=<gcp> ./deploy.sh rollback         # rolls back to last healthy revision
set -euo pipefail

REGION="${REGION:-us-east1}"
PROJECT_ID="${PROJECT_ID:-}"

# ---- Fail-closed environment validation (P0-4) ----
if [ -z "${ENV:-}" ]; then
  echo "ERROR: ENV is required (staging or prod)" >&2
  exit 1
fi
case "$ENV" in
  staging|prod) : ;;
  *)
    echo "ERROR: invalid ENV='$ENV' (must be exactly 'staging' or 'prod')" >&2
    exit 1
    ;;
esac
[ -n "$PROJECT_ID" ] || { echo "ERROR: PROJECT_ID is required" >&2; exit 1; }

# ---- Unified Artifact Registry image path (P0-3) ----
REPO="${REGION}-docker.pkg.dev/${PROJECT_ID}/nexus"
IMAGE="${REPO}/nexus-neon-backend"

# ---- Env-scoped service, service account, and Secret Manager secret names (P0-1, P0-2, P0-5) ----
case "$ENV" in
  staging)
    SERVICE="nexus-neon-backend-staging"
    SA="nexus-neon-backend-staging@${PROJECT_ID}.iam.gserviceaccount.com"
    SECRET_SUFFIX="-staging"
    ;;
  prod)
    SERVICE="nexus-neon-backend"
    SA="nexus-neon-backend@${PROJECT_ID}.iam.gserviceaccount.com"
    SECRET_SUFFIX=""
    ;;
esac
READ_SECRET="neon-read-url${SECRET_SUFFIX}"
WRITE_SECRET="neon-write-url${SECRET_SUFFIX}"
KEYS_SECRET="nexus-backend-keys${SECRET_SUFFIX}"

validate() {
  echo "== env =="
  echo "  ENV=$ENV SERVICE=$SERVICE SA=$SA"
  echo "  secrets: $READ_SECRET / $WRITE_SECRET / $KEYS_SECRET"
  echo "== validate YAML configs =="
  for f in service.yaml service-staging.yaml; do
    python3 - "$f" <<'PY'
import sys, yaml
yaml.safe_load(open(f"cloudrun/{sys.argv[1]}"))
print(f"  ok cloudrun/{sys.argv[1]}")
PY
  done
  echo "== validate no credential literals committed =="
  if grep -RniE 'NEON_(READ|WRITE)_URL=|NEXUS_BACKEND_KEY=|NEXUS_KEYS=|postgresql://[^ ]+@|jdbc:postgresql://[^ ]+@' \
      cloudrun/ service*.yaml 2>/dev/null; then
    echo "  FAIL: a credential literal is present" >&2
    exit 1
  fi
  echo "  ok no credentials committed"
}

build() {
  local tag="${1:?tag}"
  echo "== building ${IMAGE}:${tag} (Artifact Registry — billable storage) =="
  gcloud builds submit --tag "${IMAGE}:${tag}" .
}

dry_run() {
  local tag="${1:?tag}"
  echo "== dry-run deploy ${SERVICE} (${ENV}) — no service created, no billing =="
  gcloud run deploy "${SERVICE}" \
    --image "${IMAGE}:${tag}" \
    --region "${REGION}" --platform managed \
    --no-traffic --format none --dry-run 2>&1 | head -40
}

deploy() {
  local tag="${1:?tag}"
  echo "!! Deploying ${SERVICE} (${ENV}) to ${REGION} — BILLABLE. Type 'yes' to confirm:"
  read -r confirm
  [ "$confirm" = "yes" ] || { echo "aborted"; exit 1; }
  gcloud run deploy "${SERVICE}" \
    --image "${IMAGE}:${tag}" \
    --region "${REGION}" --platform managed \
    --min-instances 0 --max-instances 2 \
    --timeout 30 --concurrency 10 \
    --service-account "${SA}" \
    --set-secrets="NEON_READ_URL=${READ_SECRET}:latest,NEON_WRITE_URL=${WRITE_SECRET}:latest,NEXUS_KEYS=${KEYS_SECRET}:latest" \
    --no-cpu-throttling
}

rollback() {
  echo "== rolling back ${SERVICE} (${ENV}) to the latest READY revision before the current one =="
  # Only READY (healthy) revisions are candidates (P0-6). Newest-first; skip the current head.
  local -a ready
  mapfile -t ready < <(gcloud run revisions list --service "${SERVICE}" --region "${REGION}" \
    --filter="status.conditions.type=Ready AND status.conditions.status=True" \
    --sort-by="~metadata.creationTimestamp" \
    --format 'value(metadata.name)')
  local target=""
  local rev
  for rev in "${ready[@]:1}"; do
    [ "$rev" != "${ready[0]}" ] && { target="$rev"; break; }
  done
  [ -n "$target" ] || { echo "ERROR: no healthy READY revision to roll back to" >&2; exit 1; }
  echo "  target=${target}"
  read -r -p "Confirm rollback to ${target}? Type 'yes': " confirm
  [ "$confirm" = "yes" ] || { echo "aborted"; exit 1; }
  gcloud run services update-traffic "${SERVICE}" --region "${REGION}" --to-revisions "${target}=100"
  echo "rolled back to ${target}"
}

case "${1:-}" in
  validate) validate ;;
  build) build "${2:?tag}" ;;
  dry-run) dry_run "${2:?tag}" ;;
  deploy) deploy "${2:?tag}" ;;
  rollback) rollback ;;
  *) echo "usage: ENV=staging|prod PROJECT_ID=... $0 {validate|build|dry-run|deploy|rollback} [tag]"; exit 1 ;;
esac
