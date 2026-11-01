#!/usr/bin/env bash
# One-command Cloud Run deployment for the NEXUS Neon backend (staging/prod) + rollback + dry-run.
#
# SECURITY: deploying to Cloud Run is BILLABLE. Only run `deploy`/`promote` after explicit owner
# authorization. `dry-run` and `validate` never create billable resources.
#
# Usage:
#   ./deploy.sh validate            # lint local configs (no cloud, no billing)
#   ./deploy.sh build <tag>         # build + push the image (Artifact Registry — billable storage)
#   ./deploy.sh dry-run <tag>       # gcloud run deploy --dry-run (validates, no service created)
#   ./deploy.sh deploy <tag> [env]  # deploy to Cloud Run (env: staging|prod) — BILLABLE, confirm first
#   ./deploy.sh rollback [env]      # roll back to the previous healthy revision
set -euo pipefail

ENV="${ENV:-staging}"
PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-east1}"
SERVICE="nexus-neon-backend${ENV:+-$ENV}"
IMAGE="gcr.io/${PROJECT_ID}/nexus-neon-backend"

validate() {
  echo "== validate YAML configs =="
  for f in service.yaml service-staging.yaml; do
    python3 -c "import yaml,sys; yaml.safe_load(open('cloudrun/$f')); print('  ok cloudrun/$f')"
  done
  echo "== validate Dockerfile present =="
  test -f Dockerfile && echo "  ok Dockerfile"
  echo "== validate no credentials committed =="
  if grep -RniE 'NEON_(READ|WRITE)_URL=|NEXUS_BACKEND_KEY=|NEXUS_KEYS=' cloudrun/ service*.yaml 2>/dev/null; then
    echo "  FAIL: a credential literal is present"; exit 1
  fi
  echo "  ok no credentials committed"
}

build() {
  local tag="${1:?tag}"
  echo "== building image ${IMAGE}:${tag} (Artifact Registry — billable) =="
  gcloud builds submit --tag "${IMAGE}:${tag}" .
}

dry_run() {
  local tag="${1:?tag}"
  echo "== dry-run deploy (no service created, no billing) =="
  # --no-traffic so even a successful dry-run wouldn't serve traffic.
  gcloud run deploy "${SERVICE}" \
    --image "${IMAGE}:${tag}" \
    --region "${REGION}" \
    --platform managed \
    --no-traffic \
    --format none --dry-run 2>&1 | head -40
}

deploy() {
  local tag="${1:?tag}"
  echo "!! Deploying ${SERVICE} to ${REGION} — BILLABLE. Confirm with 'yes':"
  read -r confirm
  [ "$confirm" = "yes" ] || { echo "aborted"; exit 1; }

  local secrets=(
    "NEON_READ_URL=neon-read-url:latest"
    "NEON_WRITE_URL=neon-write-url:latest"
    "NEXUS_KEYS=nexus-backend-keys:latest"
  )
  gcloud run deploy "${SERVICE}" \
    --image "${IMAGE}:${tag}" \
    --region "${REGION}" \
    --platform managed \
    --min-instances 0 --max-instances 2 \
    --timeout 30 --concurrency 10 \
    --service-account "nexus-neon-backend@${PROJECT_ID}.iam.gserviceaccount.com" \
    --set-secrets="$(IFS=,; echo "${secrets[*]}")" \
    --no-cpu-throttling
}

rollback() {
  echo "== rolling back ${SERVICE} to previous healthy revision =="
  local rev
  rev=$(gcloud run revisions list --service "${SERVICE}" --region "${REGION}" --limit 2 \
    --format 'value(metadata.name)' | sed -n 2p)
  [ -n "$rev" ] || { echo "no previous revision found"; exit 1; }
  gcloud run services update-traffic "${SERVICE}" --region "${REGION}" --to-revisions "${rev}=100"
  echo "rolled back to ${rev}"
}

case "${1:-}" in
  validate) validate ;;
  build) build "${2:?tag}" ;;
  dry-run) dry_run "${2:?tag}" ;;
  deploy) deploy "${2:?tag}" ;;
  rollback) rollback ;;
  *) echo "usage: $0 {validate|build|dry-run|deploy|rollback} [tag]"; exit 1 ;;
esac
