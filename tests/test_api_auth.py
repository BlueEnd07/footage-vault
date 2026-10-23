"""The PIN gate, CSRF guard and security headers, exercised through the real app."""
import pytest
from fastapi.testclient import TestClient

from vault import auth, config, db
from vault.main import app


@pytest.fixture()
def client(monkeypatch):
    db.init()
    monkeypatch.setattr(auth, "limiter", auth.LoginLimiter(limit=2, base_delay=60))
    config.save_settings({**config.DEFAULTS, "pin": "12345678", "sessions": {}})
    return TestClient(app)


def test_phone_without_a_session_gets_the_pin_page(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "Enter the PIN" in r.text
    assert "<script" not in r.text


def test_api_without_a_session_is_401(client):
    r = client.get("/api/videos")
    assert r.status_code == 401
    assert r.json()["detail"] == "PIN required"


def test_wrong_pin_is_rejected_and_right_pin_starts_a_session(client):
    assert client.post("/pin", content="pin=00000000").status_code == 401
    r = client.post("/pin", content="pin=12345678")
    assert r.status_code == 200
    assert auth.SESSION_COOKIE in r.cookies
    assert "AppleWebKit" not in r.text

    # The cookie allows the app shell and API from a phone (not localhost).
    assert "Footage Vault" in client.get("/").text
    assert client.get("/api/videos").status_code != 401


def test_repeated_wrong_pins_are_rate_limited(client):
    assert client.post("/pin", content="pin=11111111").status_code == 401
    r = client.post("/pin", content="pin=11111111")
    assert r.status_code == 429
    assert "Retry-After" in r.headers
    # Even the correct PIN now waits.
    assert client.post("/pin", content="pin=12345678").status_code == 429


@pytest.mark.parametrize("headers", [
    {"sec-fetch-site": "cross-site"},
    {"origin": "https://evil.example"},
    {"origin": "http://localhost:9999"},
])
def test_cross_site_writes_are_blocked(client, headers):
    r = client.post("/api/watch", json={"folder": "C:/videos"}, headers=headers)
    assert r.status_code == 403


def test_security_headers_are_set(client):
    r = client.get("/")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
    assert r.headers["referrer-policy"] == "no-referrer"
    csp = r.headers["content-security-policy"]
    assert "frame-ancestors 'none'" in csp
    assert "script-src 'self'" in csp
    assert "connect-src 'self'" in csp


def test_status_never_exposes_the_pin(client):
    client.post("/pin", content="pin=12345678")
    r = client.get("/api/status")
    assert r.status_code == 200
    assert "pin" not in r.json()
