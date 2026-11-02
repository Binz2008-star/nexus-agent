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
# `validate` is local-only. `dry-run` calls gcloud but must not create a Cloud Run service.
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
    MAX_INSTANCES=1
    CONCURRENCY=5
    ;;
  prod)
    SERVICE="nexus-neon-backend"
    SA="nexus-neon-backend@${PROJECT_ID}.iam.gserviceaccount.com"
    SECRET_SUFFIX=""
    MAX_INSTANCES=2
    CONCURRENCY=10
    ;;
esac
READ_SECRET="neon-read-url${SECRET_SUFFIX}"
WRITE_SECRET="neon-write-url${SECRET_SUFFIX}"
KEYS_SECRET="nexus-backend-keys${SECRET_SUFFIX}"

validate() {
  echo "== env == ENV=$ENV SERVICE=$SERVICE SA=$SA"
  echo "== validating both service manifests and staging isolation =="
  # Prefer structural validation over grep of deploy.sh, which contains secret
  # reference expressions but never literal connection strings.
  python3 - "$ENV" "$SERVICE" "$SA" <<'PY'
import pathlib, re, sys
try:
    import yaml
except ImportError:
    sys.exit("Install PyYAML before validation: python3 -m pip install PyYAML==6.0.2")
root = pathlib.Path("cloudrun")
env, service, sa = sys.argv[1:]
for filename, suffix in (("service.yaml", ""), ("service-staging.yaml", "-staging")):
    path = root / filename
    data = yaml.safe_load(path.read_text())
    assert data["metadata"]["name"] == "nexus-neon-backend" + suffix
    spec = data["spec"]["template"]["spec"]
    assert spec["serviceAccountName"] == "nexus-neon-backend" + suffix + "@PROJECT.iam.gserviceaccount.com"
    assert spec["containerConcurrency"] == (5 if suffix else 10)
    assert int(data["spec"]["template"]["metadata"]["annotations"]["autoscaling.knative.dev/minScale"]) == 0
    assert int(data["spec"]["template"]["metadata"]["annotations"]["autoscaling.knative.dev/maxScale"]) == (1 if suffix else 2)
    container = spec["containers"][0]
    assert container["resources"]["limits"]["memory"] == "512Mi"
    secrets = {i["name"]: i["valueFrom"]["secretKeyRef"]["name"]
               for i in container["env"] if "valueFrom" in i}
    expected = {
        "NEON_READ_URL": "neon-read-url" + suffix,
        "NEON_WRITE_URL": "neon-write-url" + suffix,
        "NEXUS_KEYS": "nexus-backend-keys" + suffix,
    }
    assert secrets == expected, f"{filename}: unexpected secret refs"
    assert "postgresql://" not in path.read_text()
    print(f"  ok {filename}")
assert service == "nexus-neon-backend" + ("-staging" if env == "staging" else "")
assert sa.startswith("nexus-neon-backend" + ("-staging" if env == "staging" else "") + "@")
PY
  echo "ok both Cloud Run manifests; no credential literals"
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
    --min-instances 0 --max-instances "${MAX_INSTANCES}" \
    --timeout 30 --concurrency "${CONCURRENCY}" \
    --memory 512Mi --cpu 1 --port 8080 --cpu-throttling \
    --service-account "${SA}" \
    --set-secrets="NEON_READ_URL=${READ_SECRET}:latest,NEON_WRITE_URL=${WRITE_SECRET}:latest,NEXUS_KEYS=${KEYS_SECRET}:latest"
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
