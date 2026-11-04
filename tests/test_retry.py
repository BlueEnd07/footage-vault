"""Retry all: failed uploads go back in the queue, held ones get re-checked, others are left alone."""
from fastapi.testclient import TestClient

from vault import config, db
from vault.main import app


def _add(name, status):
    return db.run("""INSERT INTO videos (fingerprint, name, source, path, size, recorded_at, imported_at, status, attempts, error)
                     VALUES (?, ?, 'pc', ?, 1, '2026-01-01', '2026-01-01', ?, 3, 'boom')""",
                  (name, name, f"C:/v/{name}", status))


def test_retry_all_requeues_only_problem_videos():
    db.init()
    db.run("DELETE FROM videos")
    _add("a.mp4", "failed")
    _add("b.mp4", "hold")
    _add("c.mp4", "on_youtube")
    config.save_settings({**config.DEFAULTS, "pin": "12345678", "sessions": {}})
    client = TestClient(app)
    assert client.post("/pin", content="pin=12345678").status_code == 200

    r = client.post("/api/videos/retry-all", json={})
    assert r.status_code == 200 and r.json()["count"] == 2

    got = {v["name"]: v for v in db.rows("SELECT name, status, attempts, error FROM videos")}
    assert got["a.mp4"]["status"] == "new"  # no preview yet, so it starts from the top
    assert got["a.mp4"]["attempts"] == 0 and got["a.mp4"]["error"] is None
    assert got["b.mp4"]["status"] == "on_youtube" and got["b.mp4"]["error"] is None
    assert got["c.mp4"]["error"] == "boom"

    assert client.post("/api/videos/retry-all", json={}).json()["count"] == 0
