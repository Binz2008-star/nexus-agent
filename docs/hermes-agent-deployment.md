# Hermes Agent — Deployment Guide (separate host)

This guide covers running a Hermes Agent API server on a **separate host** and connecting NEXUS
(Android) to it securely. The Android app only talks to this server over HTTPS; the server runs
its own tools in its own environment.

## 1. Host requirements

- A Linux server / VPS (the desktop/server installer) with Python + Node available (the Hermes
  installer manages these).
- A domain or static IP and a TLS endpoint reachable from the phone. **The app requires HTTPS for
  any non-loopback host.**

## 2. Install and configure Hermes (read-only profile)

Install per the official docs (Linux/macOS):

```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
source ~/.bashrc
hermes setup            # configure a model/provider
```

### Restrict the toolset (read-only first — REQUIRED)

The API server exposes the **full** toolset, including the terminal. For a phone-connected
deployment you must start with a restricted, read-only profile. Disable terminal, file-write, and
device-control tools; keep read-only tools (read, web search, memory). Configure this via Hermes's
tool/profile settings (see the Hermes tools documentation for the exact config keys for your
version) and verify with `/v1/toolsets`:

```bash
curl -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8642/v1/toolsets
```

Confirm no `write_file`, `terminal`, or device-control tools are enabled before exposing the port.

### Enable the API server

```bash
cat >> ~/.hermes/.env <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0            # bind for remote access (see warning)
API_SERVER_PORT=8642
API_SERVER_KEY=<strong-random-key>
EOF
hermes gateway
```

> **Warning:** `API_SERVER_KEY` is a bearer token with full tool access. Use a long random value,
> keep it out of version control, and never commit it to the NEXUS repo. Binding to `0.0.0.0`
> without TLS exposes it in cleartext — see §3.

## 3. Secure network access from Android

The app requires `https://` for remote hosts. Terminate TLS in front of the API server with a
reverse proxy (Caddy/nginx) or a tunnel (Tailscale/WireGuard/Cloudflare Tunnel):

```bash
# Caddy example (auto-TLS):
caddy reverse-proxy --from your.hermes.example.com --to 127.0.0.1:8642
```

Then the NEXUS endpoint is `https://your.hermes.example.com` (no path — the app appends `/v1/...`
and `/health`). For local-dev only, `http://127.0.0.1:8642` is allowed.

## 4. Connect NEXUS

1. In the app, open **Settings**.
2. Enter the Hermes endpoint (`https://your.hermes.example.com`) and the API server key.
3. Tap **Verify**. The app calls `/v1/capabilities` and `/health` and checks the Runs API is
   advertised. When it reports **Connected & verified**, the **Hermes (Beta)** engine becomes
   selectable.

## 5. Operations notes

- **Approvals:** a gated tool on the host surfaces an approval card in NEXUS; approving sends
  `choice: once`, rejecting sends `choice: deny`. This is the app-level gate only — configure
  Hermes's own approval policy server-side as well.
- **Cancellation:** cancelling in NEXUS calls `/v1/runs/{id}/stop` (best-effort).
- **Audit:** the app writes a local, secret-free audit log to its files dir
  (`hermes_audit.log.jsonl`); the server keeps its own logs on the host.
- **Memory:** Hermes long-term memory is scoped per NEXUS conversation; NEXUS Neon remains the
  source of truth for personal memory and is not sent to the host.
- **Concurrency:** the API server caps concurrent runs (default 10); expect HTTP 429 under load.
