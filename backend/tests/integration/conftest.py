"""Integration-test harness: runs against a disposable PostgreSQL (docker-compose.test.yml).

Set NEON_READ_URL / NEON_WRITE_URL / NEXUS_BACKEND_KEY to enable; otherwise every test here skips
so the plain `pytest` run (no DB) still passes.
"""
import os
from urllib.parse import urlsplit, urlunsplit

import pytest

NEON_READ_URL = os.environ.get("NEON_READ_URL", "")
NEON_WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
BACKEND_KEY = os.environ.get("NEXUS_BACKEND_KEY", "")

AVAILABLE = bool(NEON_READ_URL and NEON_WRITE_URL and BACKEND_KEY)

# All tests in this directory require a live database.
pytestmark = pytest.mark.integration


def _swapped(url: str, user: str, password: str, db: str) -> str:
    """Rebuild `url` with a different role/database, keeping scheme/host/port (disposable env)."""
    parts = urlsplit(url)
    netloc = f"{user}:{password}@{parts.hostname}:{parts.port or 5432}"
    return urlunsplit((parts.scheme, netloc, f"/{db}", parts.query, parts.fragment))


@pytest.fixture(autouse=True)
def _require_db():
    if not AVAILABLE:
        pytest.skip("integration env not configured (NEON_READ_URL/NEON_WRITE_URL/NEXUS_BACKEND_KEY)")


@pytest.fixture()
def client():
    import main

    main.READ_URL = NEON_READ_URL
    main.WRITE_URL = NEON_WRITE_URL
    main.API_KEY = BACKEND_KEY

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
def reader_write_url():
    # nexus_reader pointed at personal_ai_core (has no INSERT -> permission failure).
    return _swapped(NEON_WRITE_URL, "nexus_reader", "readerpass", "personal_ai_core")


@pytest.fixture()
def writer_read_url():
    # nexus_writer pointed at second_brain (has no SELECT on chunks_v4 -> permission failure).
    return _swapped(NEON_READ_URL, "nexus_writer", "writerpass", "second_brain")
