# Cloud Run — staging cost estimate, spending controls, rollback

Reference only. **No billable resources are created without owner authorization.**

## Staging cost estimate (us-east1, scale-to-zero)

| Resource | Config | Approx. cost |
|---|---|---|
| Cloud Run (staging) | min 0 / max 1 instance, 1 vCPU / 512 MiB, scale-to-zero | **~$0 idle**; ~$0.000024/vCPU-s + $0.0000025/GiB-s while serving |
| Artifact Registry | one image (~120 MB) | ~$0.01–0.10/month |
| Secret Manager | 3 staging secrets | ~$0.18/month (3 × $0.06 active-secret) |

A typical parity-test burst (a few minutes of a single 1-vCPU/512 MiB instance) costs **well under $0.01**.

## Maximum spending controls

1. **Scale bounds** — `minScale: 0` (idle = free) + `maxScale: 1` (staging) / `2` (prod). No horizontal blow-up.
2. **Instance size** — `1 vCPU / 512 MiB` (smallest useful); `containerConcurrency` 5–10.
3. **Timeout** — 30 s per request; `NEXUS_CONNECT_TIMEOUT=10`, `NEXUS_RATE_LIMIT_PER_MIN=120`.
4. **Budget alert** (recommended, one-time owner action):
   ```bash
   gcloud billing budgets create --billing-account=<ID> --display-name="NEXUS-Neon" \
     --budget-amount=5USD --threshold-rule=percent=0.5,1.0
   ```
5. **No autoscaling surprises** — the backend has no cron/scheduled job; it only serves app requests.

## Rollback procedure

Cloud Run keeps revisions. To roll back, run (or use `./deploy.sh rollback`):

```bash
ENV=staging PROJECT_ID=<gcp> ./deploy.sh rollback
```

The script targets the **latest READY (healthy) revision** before the current one — never merely the
second-newest (which could be a failed revision). It confirms before changing traffic.

Manual equivalent:
```bash
gcloud run revisions list --service nexus-neon-backend-staging --region us-east1
gcloud run services update-traffic nexus-neon-backend-staging --region us-east1 --to-revisions <GOOD>=100
```

Rollback is **non-disruptive to the app**: it talks HTTPS with a bearer key; no app release is
required. The Android flag stays `use_neon_backend=false` (JDBC) as the fallback until parity passes.
