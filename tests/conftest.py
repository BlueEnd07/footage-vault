"""Tests use a throwaway VAULT_DATA folder, never the real library."""
import os
import tempfile

os.environ["VAULT_DATA"] = tempfile.mkdtemp(prefix="vault-test-")

import pytest

from vault import config


@pytest.fixture(autouse=True)
def _fresh_settings(tmp_path, monkeypatch):
    config._cache["key"] = None
    config._cache["value"] = None
    monkeypatch.setattr(config, "SETTINGS_PATH", tmp_path / "settings.json")
    yield
    config._cache["key"] = None
    config._cache["value"] = None
