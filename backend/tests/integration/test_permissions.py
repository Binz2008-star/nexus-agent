"""Permission-failure parity: a misconfigured role must fail cleanly (502 + stable code), never
crash or leak a raw DB error. Uses the disposable env's least-privilege roles."""
import main


def test_reader_role_cannot_write(client, auth, reader_write_url):
    # nexus_reader has no INSERT grant on personal_ai_core.nexus_memory -> "permission".
    original = main.WRITE_URL
    main.WRITE_URL = reader_write_url
    try:
        r = client.post("/v1/memory/save", json={"type": "fact", "content": "x"}, headers=auth)
        assert r.status_code == 502
        assert r.json()["detail"] == "permission"
    finally:
        main.WRITE_URL = original


def test_writer_role_cannot_read_chunks(client, auth, writer_read_url):
    # nexus_writer has no SELECT grant on second_brain.chunks_v4 -> "permission".
    original = main.READ_URL
    main.READ_URL = writer_read_url
    try:
        r = client.post("/v1/brain/search", json={"query": "x"}, headers=auth)
        assert r.status_code == 502
        assert r.json()["detail"] == "permission"
    finally:
        main.READ_URL = original
