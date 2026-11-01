"""End-to-end HTTPS tests against the DEPLOYED backend. Enabled only when NEXUS_BACKEND_URL and
NEXUS_BACKEND_KEY are set (post-deploy); skipped otherwise. These exercise the real wire contract
(TLS, auth, authorization, timeouts, error recovery, rate limits) — not the in-process TestClient."""
import os

import httpx
import pytest

BASE_URL = os.environ.get("NEXUS_BACKEND_URL", "").rstrip("/")
KEY = os.environ.get("NEXUS_BACKEND_KEY", "")

AVAILABLE = bool(BASE_URL and KEY)

pytestmark = pytest.mark.e2e


@pytest.fixture(autouse=True)
def _require_e2e():
    if not AVAILABLE:
        pytest.skip("e2e env not configured (NEXUS_BACKEND_URL / NEXUS_BACKEND_KEY)")


@pytest.fixture()
def client():
    return httpx.Client(base_url=BASE_URL, timeout=15.0)


@pytest.fixture()
def auth():
    return {"Authorization": f"Bearer {KEY}"}
