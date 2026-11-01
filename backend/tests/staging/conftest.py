"""Live-parity harness against Neon staging (data-agnostic — no disposable fixtures).

Enabled only when NEON_READ_URL / NEON_WRITE_URL / NEXUS_BACKEND_KEY are set (the `staging-parity`
CI job). A separate `NEXUS_STAGING_ADMIN_URL` (owner role) verifies transcript-mirror writes, since
the `nexus` role cannot SELECT `nexus_conversations` by design.
"""
import os

import pytest

NEON_READ_URL = os.environ.get("NEON_READ_URL", "")
NEON_WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
BACKEND_KEY = os.environ.get("NEXUS_BACKEND_KEY", "")
ADMIN_URL = os.environ.get("NEXUS_STAGING_ADMIN_URL", "")

AVAILABLE = bool(NEON_READ_URL and NEON_WRITE_URL and BACKEND_KEY and ADMIN_URL)

pytestmark = pytest.mark.staging


@pytest.fixture(autouse=True)
def _require_staging():
    if not AVAILABLE:
        pytest.skip("staging env not configured (NEON_READ_URL/NEON_WRITE_URL/NEXUS_BACKEND_KEY/NEXUS_STAGING_ADMIN_URL)")


@pytest.fixture()
def client():
    import main
    from auth import ALL_SCOPES, Identity, RateLimiter

    main.READ_URL = NEON_READ_URL
    main.WRITE_URL = NEON_WRITE_URL
    main.IDENTITIES = {BACKEND_KEY: Identity(name="staging", scopes=ALL_SCOPES)}
    main.RATE_LIMITER = RateLimiter(per_min=0)

    from fastapi.testclient import TestClient

    return TestClient(main.app)


@pytest.fixture()
def auth():
    return {"Authorization": f"Bearer {BACKEND_KEY}"}


@pytest.fixture()
def read_url():
    return NEON_READ_URL


@pytest.fixture()
def write_url():
    return NEON_WRITE_URL


@pytest.fixture()
def admin_url():
    return ADMIN_URL
