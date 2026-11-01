"""Deployment-isolation regression tests for backend/cloudrun/deploy.sh (P0 hardening).

These verify the fail-closed environment validation, env-scoped secrets/service-account, unified
image path, healthy-revision rollback, and absence of credential literals in the deployment configs.
"""
import os
import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]  # backend/
DEPLOY = ROOT / "cloudrun" / "deploy.sh"


def _script() -> str:
    if not DEPLOY.exists():
        pytest.skip("deploy.sh not present (backend-only checkout)")
    return DEPLOY.read_text()


def _run(args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(["bash", str(DEPLOY), *args], capture_output=True, text=True, env=env)


def test_env_validation_fails_closed():
    s = _script()
    assert 'case "$ENV" in' in s
    assert "staging|prod)" in s
    assert "invalid ENV=" in s
    # Missing ENV -> non-zero (before any cloud call).
    assert _run(["validate"], dict(os.environ)).returncode != 0
    # Unrecognized ENV -> non-zero.
    assert _run(["validate"], {**os.environ, "ENV": "dev"}).returncode != 0


def test_missing_project_id_fails_closed():
    assert _run(["validate"], {**os.environ, "ENV": "staging"}).returncode != 0


def test_staging_uses_staging_secrets_and_service_account():
    s = _script()
    assert "nexus-neon-backend-staging" in s
    assert "nexus-neon-backend-staging@" in s
    assert 'SECRET_SUFFIX="-staging"' in s


def test_prod_uses_prod_service_account_without_staging_suffix():
    s = _script()
    assert 'nexus-neon-backend@${PROJECT_ID}' in s
    assert 'SECRET_SUFFIX=""' in s


def test_unified_image_path_on_artifact_registry():
    s = _script()
    assert "docker.pkg.dev" in s
    assert "gcr.io" not in s  # legacy registry path removed


def test_rollback_targets_ready_revision():
    s = _script()
    assert "status.conditions.type=Ready" in s  # filter to healthy revisions only
    assert "no healthy READY revision" in s


def test_no_credential_literals_in_deployment_configs():
    for name in ("service.yaml", "service-staging.yaml"):
        path = ROOT / "cloudrun" / name
        if not path.exists():
            continue
        text = path.read_text()
        assert not re.search(r"postgresql://[^ ]+@", text), f"{name} contains a connection string"
        assert not re.search(r"jdbc:postgresql://[^ ]+@", text), f"{name} contains a JDBC URL"
        assert "secretKeyRef" in text, f"{name} should use Secret Manager refs, not literals"
