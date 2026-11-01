# Cloud Run deployment — NEXUS Neon backend

Reference procedures. **Do not run any of this until the owner authorizes billable
infrastructure.** Everything is reversible and uses Secret Manager (no credential in the repo or
the image).

## 0. One-time setup (project-level, non-billable)

```bash
export PROJECT_ID=<gcp-project>
gcloud config set project "$PROJECT_ID"

# Artifact Registry (staging location for the image)
gcloud services enable artifactregistry.googleapis.com run.googleapis.com secretmanager.googleapis.com
gcloud artifacts repositories create nexus --repository-format=docker --location=us-east1
```

## 1. Build & push the image

```bash
cd backend
gcloud builds submit \
  --region=us-east1 \
  --tag "us-east1-docker.pkg.dev/$PROJECT_ID/nexus/nexus-neon-backend:$(git rev-parse --short HEAD)"
```

## 2. Secrets (Secret Manager — values never committed)

```bash
printf '%s' '<read-url>'  | gcloud secrets create neon-read-url  --data-file=- --replication-policy=automatic
printf '%s' '<write-url>' | gcloud secrets create neon-write-url --data-file=- --replication-policy=automatic
printf '%s' '<keys-json>'  | gcloud secrets create nexus-backend-keys --data-file=- --replication-policy=automatic
```

`nexus-backend-keys` is a JSON object `{"<api-key>": {"name": "phone", "scopes": ["read","write"]}}`
(see `../auth.py`). Each device gets its own key + scopes; `write` grants memory/mirror writes.

Grant the runtime service account access to read the secrets:

```bash
gcloud secrets add-iam-policy-binding neon-read-url \
  --member="serviceAccount:nexus-neon-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor
# … repeat for neon-write-url and nexus-backend-keys
```

## 3. Deploy (us-east1)

```bash
gcloud run deploy nexus-neon-backend \
  --image "us-east1-docker.pkg.dev/$PROJECT_ID/nexus/nexus-neon-backend:$(git rev-parse --short HEAD)" \
  --region us-east1 \
  --platform managed \
  --service-account "nexus-neon-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --set-secrets=NEON_READ_URL=neon-read-url:latest,NEON_WRITE_URL=neon-write-url:latest,NEXUS_KEYS=nexus-backend-keys:latest \
  --min-instances 0 --max-instances 2 \
  --timeout 30 --concurrency 10 \
  --allow-unauthenticated   # auth is the bearer key, not GCP IAM
```

## 4. Health checks

- `GET /health` → `{"status":"ok"}` (liveness/startup probe path).
- `GET /health` is unauthenticated and DB-free by design (it must not fail when Neon is down).
- Real DB reachability is exercised by authenticated traffic; a Neon outage surfaces as 502
  with a stable `detail` code (`network`/`auth`/…), never a crash.

## 5. Rollback

Cloud Run keeps revisions; rollback is a traffic split to the previous healthy revision:

```bash
# List revisions
gcloud run revisions list --service nexus-neon-backend --region us-east1

# Point 100% back at a known-good revision
gcloud run services update-traffic nexus-neon-backend \
  --region us-east1 \
  --to-revisions REVISION_GOOD=100

# Or, with gradual rollback:
#   --to-revisions REVISION_GOOD=50,REVISION_BAD=50
```

Because the app talks to the service over HTTPS with a bearer key (not a schema change), rollback
does **not** require an app release — the Android flag stays `use_neon_backend=false` (JDBC) as the
fallback until parity passes.

## 6. Do NOT

- Commit `NEON_READ_URL`/`NEON_WRITE_URL`/`NEXUS_KEYS`/`NEXUS_BACKEND_KEY` anywhere.
- Enable billing or `gcloud run deploy` until the owner authorizes it.
- Grant the service account anything beyond `roles/secretmanager.secretAccessor` on those 3 secrets.
