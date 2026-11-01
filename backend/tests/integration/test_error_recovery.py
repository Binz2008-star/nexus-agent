"""Error recovery: a Neon outage surfaces as a clean 502 (stable code) and the service recovers
when the database is reachable again."""
import pytest

import main

pytestmark = pytest.mark.integration


def test_db_down_maps_to_502(client, auth):
    # Port 1 has no listener -> immediate "connection refused" -> network.
    original = main.READ_URL
    main.READ_URL = "postgresql://nouser:nopass@127.0.0.1:1/nope"
    try:
        r = client.post("/v1/brain/search", json={"query": "x"}, headers=auth)
        assert r.status_code == 502
        assert r.json()["detail"] in ("network", "server")
    finally:
        main.READ_URL = original


def test_service_recovers_after_db_outage(client, auth):
    original = main.READ_URL
    try:
        main.READ_URL = "postgresql://nouser:nopass@127.0.0.1:1/nope"
        assert client.post("/v1/brain/search", json={"query": "x"}, headers=auth).status_code == 502
    finally:
        main.READ_URL = original

    # Restored connection -> 200 again (no poisoned state, no cached connection).
    r = client.post("/v1/brain/search", json={"query": "color"}, headers=auth)
    assert r.status_code == 200
