"""Static and local-only Cloud Run deployment parity and isolation checks.

Tests never run cloud builds, gcloud deploy or mutate project configuration.
"""
import os
import re
import subprocess
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "cloudrun" / "deploy.sh"


def _script() -> str:
    assert DEPLOY.is_file(), "deploy.sh must be tracked"
    return DEPLOY.read_text()


def _run(args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", str(DEPLOY), *args],
        capture_output=True, text=True,
        env=env, cwd=ROOT, check=False,
    )


def _env(**overrides: str) -> dict[str, str]:
    base = {k: v for k, v in os.environ.items() if k not in ("ENV", "PROJECT_ID")}
    return {**base, **overrides}


def test_validation_requires_explicit_valid_environment_and_project():
    assert _run(["validate"], _env()).returncode != 0
    assert _run(["validate"], _env(ENV="dev", PROJECT_ID="example")).returncode != 0
    assert _run(["validate"], _env(ENV="staging")).returncode != 0
    assert _run(["validate"], _env(ENV="prod", PROJECT_ID="example")).returncode == 0
    assert _run(["validate"], _env(ENV="staging", PROJECT_ID="example")).returncode == 0


def test_both_manifests_are_required_and_structurally_valid():
    for filename, suffix, replicas, concurrency in (
        ("service.yaml", "", 2, 10),
        ("service-staging.yaml", "-staging", 1, 5),
    ):
        path = ROOT / "cloudrun" / filename
        assert path.is_file(), f"missing required {filename}"
        document = yaml.safe_load(path.read_text())
        assert document["metadata"]["name"] == "nexus-neon-backend" + suffix
        spec = document["spec"]["template"]["spec"]
        assert spec["serviceAccountName"] == (
            "nexus-neon-backend" + suffix + "@PROJECT.iam.gserviceaccount.com"
        )
        assert spec["containerConcurrency"] == concurrency
        annotations = document["spec"]["template"]["metadata"]["annotations"]
        assert int(annotations["autoscaling.knative.dev/minScale"]) == 0
        assert int(annotations["autoscaling.knative.dev/maxScale"]) == replicas
        container = spec["containers"][0]
        assert container["resources"]["limits"]["memory"] == "512Mi"
        secrets = {
            e["name"]: e["valueFrom"]["secretKeyRef"]["name"]
            for e in container["env"] if "valueFrom" in e
        }
        assert secrets == {
            "NEON_READ_URL": "neon-read-url" + suffix,
            "NEON_WRITE_URL": "neon-write-url" + suffix,
            "NEXUS_KEYS": "nexus-backend-keys" + suffix,
        }
        assert "postgresql://" not in path.read_text()
        assert "jdbc:postgresql://" not in path.read_text()


def test_deploy_command_uses_environment_specific_values():
    script = _script()
    assert 'case "$ENV" in' in script
    assert 'SECRET_SUFFIX="-staging"' in script
    assert 'MAX_INSTANCES=1' in script
    assert 'CONCURRENCY=5' in script
    assert 'MAX_INSTANCES=2' in script
    assert 'CONCURRENCY=10' in script
    assert '--max-instances "${MAX_INSTANCES}"' in script
    assert '--concurrency "${CONCURRENCY}"' in script
    assert "--cpu-throttling" in script
    assert "--no-cpu-throttling" not in script
    assert "docker.pkg.dev" in script
    assert "gcr.io" not in script
    assert "--service-account \"${SA}\"" in script


def test_rollback_only_targets_ready_revision_and_requires_confirmation():
    script = _script()
    assert "status.conditions.type=Ready" in script
    assert "no healthy READY revision" in script
    assert 'Confirm rollback' in script


def test_no_inline_credential_values_in_manifest():
    for name in ("service.yaml", "service-staging.yaml"):
        data = (ROOT / "cloudrun" / name).read_text()
        assert not re.search(r"postgresql://[^ ]+@", data), name
        assert not re.search(r"jdbc:postgresql://[^ ]+@", data), name
        assert "secretKeyRef" in data, name
