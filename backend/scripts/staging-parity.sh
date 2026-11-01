#!/usr/bin/env bash
# Live parity: fetch Neon staging connection strings via neonctl, then run the data-agnostic
# staging parity tests. Never touches production (staging branches only).
#
# Required env: NEON_API_KEY, NEON_ORG_ID,
#               READ_PROJECT_ID, READ_BRANCH_ID, WRITE_PROJECT_ID, WRITE_BRANCH_ID
set -euo pipefail

neonctl() { npx --yes neonctl@8.16.0 "$@"; }

READ_URL=$(neonctl connection-string --org-id "$NEON_ORG_ID" \
  --project-id "$READ_PROJECT_ID" --branch-id "$READ_BRANCH_ID" --role-name nexus_reader \
  2>/dev/null | grep -oE 'postgresql://[^ ]+')

WRITE_URL=$(neonctl connection-string --org-id "$NEON_ORG_ID" \
  --project-id "$WRITE_PROJECT_ID" --branch-id "$WRITE_BRANCH_ID" --role-name nexus \
  2>/dev/null | grep -oE 'postgresql://[^ ]+')

# Owner role (neondb_owner) for verifying transcript-mirror writes (nexus can't SELECT it).
ADMIN_URL=$(neonctl connection-string --org-id "$NEON_ORG_ID" \
  --project-id "$WRITE_PROJECT_ID" --branch-id "$WRITE_BRANCH_ID" \
  2>/dev/null | grep -oE 'postgresql://[^ ]+')

[ -n "$READ_URL" ] && [ -n "$WRITE_URL" ] && [ -n "$ADMIN_URL" ] || {
  echo "ERROR: failed to resolve staging connection strings" >&2; exit 1; }

export NEON_READ_URL="$READ_URL"
export NEON_WRITE_URL="$WRITE_URL"
export NEXUS_STAGING_ADMIN_URL="$ADMIN_URL"
export NEXUS_BACKEND_KEY="${NEXUS_BACKEND_KEY:-staging-parity-ci}"

cd "$(dirname "$0")/.."   # backend/
pytest -m staging -q
