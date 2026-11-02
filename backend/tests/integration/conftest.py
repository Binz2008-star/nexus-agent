"""Integration-test harness: runs against a disposable PostgreSQL (docker-compose.test.yml).

Set NEON_READ_URL / NEON_WRITE_URL / NEON_MEMORY_READ_URL / NEXUS_BACKEND_KEY
to enable; otherwise every test here skips
so the plain `pytest` run (no DB) still passes.
"""
import os
from urllib.parse import quote, urlsplit, urlunsplit

import pytest

NEON_READ_URL = os.environ.get("NEON_READ_URL", "")
NEON_WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
NEON_MEMORY_READ_URL = os.environ.get("NEON_MEMORY_READ_URL", "")
BACKEND_KEY = os.environ.get("NEXUS_BACKEND_KEY", "")

# Role/database parameters for the disposable env. Overridable so the same suite can run against a
# Neon staging branch, whose role/db names differ and which exposes no `postgres` superuser.
READ_ROLE = os.environ.get("NEXUS_TEST_READ_ROLE", "nexus_reader")
READ_PASSWORD = os.environ.get("NEXUS_TEST_READ_PASSWORD", "readerpass")
WRITE_ROLE = os.environ.get("NEXUS_TEST_WRITE_ROLE", "nexus_writer")
WRITE_PASSWORD = os.environ.get("NEXUS_TEST_WRITE_PASSWORD", "writerpass")
READ_DB = os.environ.get("NEXUS_TEST_READ_DB", "second_brain")
WRITE_DB = os.environ.get("NEXUS_TEST_WRITE_DB", "personal_ai_core")
ADMIN_ROLE = os.environ.get("NEXUS_TEST_ADMIN_ROLE", "postgres")
ADMIN_PASSWORD = os.environ.get("NEXUS_TEST_ADMIN_PASSWORD", "test")
ADMIN_DB = os.environ.get("NEXUS_TEST_ADMIN_DB", "personal_ai_core")

AVAILABLE = bool(NEON_READ_URL and NEON_WRITE_URL and NEON_MEMORY_READ_URL and BACKEND_KEY)

# All tests in this directory require a live database; the per-file `pytestmark` marks them
# `integration` (conftest's own pytestmark is not applied to test items).


def _swapped(url: str, user: str, password: str, db: str) -> str:
    """Rebuild `url` with a different role/database, keeping scheme/host/port + query. Percent-encodes
    user/password/db so Neon-generated passwords with special characters don't corrupt the URL."""
    parts = urlsplit(url)
    netloc = f"{quote(user, safe='')}:{quote(password, safe='')}@{parts.hostname}:{parts.port or 5432}"
    return urlunsplit((parts.scheme, netloc, "/" + quote(db, safe=""), parts.query, parts.fragment))


@pytest.fixture(autouse=True)
def _require_db():
    if not AVAILABLE:
        pytest.skip("integration env not configured (NEON_READ_URL/NEON_WRITE_URL/NEON_MEMORY_READ_URL/NEXUS_BACKEND_KEY)")


@pytest.fixture()
def client():
    import main
    from auth import ALL_SCOPES, Identity, RateLimiter

    main.READ_URL = NEON_READ_URL
    main.WRITE_URL = NEON_WRITE_URL
    main.MEMORY_READ_URL = NEON_MEMORY_READ_URL
    main.IDENTITIES = {BACKEND_KEY: Identity(name="test", scopes=ALL_SCOPES)}
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
def memory_read_url():
    return NEON_MEMORY_READ_URL


@pytest.fixture()
def reader_write_url():
    # read role pointed at the write DB (has no INSERT -> permission failure).
    return _swapped(NEON_WRITE_URL, READ_ROLE, READ_PASSWORD, WRITE_DB)


@pytest.fixture()
def writer_read_url():
    # write role pointed at the read DB (has no SELECT on chunks_v4 -> permission failure).
    return _swapped(NEON_READ_URL, WRITE_ROLE, WRITE_PASSWORD, READ_DB)


@pytest.fixture()
def admin_write_url():
    # Privileged connection for verifying writes the app role is not allowed to read back.
    # Local env uses the `postgres` superuser; a Neon staging branch must supply a restricted
    # verifier role instead (Neon exposes no superuser).
    return _swapped(NEON_WRITE_URL, ADMIN_ROLE, ADMIN_PASSWORD, ADMIN_DB)
