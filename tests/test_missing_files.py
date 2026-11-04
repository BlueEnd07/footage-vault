"""A video whose card / drive was unplugged must not block the upload queue."""
from vault import db, worker


def _add(name, path, recorded):
    return db.run("""INSERT INTO videos (fingerprint, name, source, path, size, recorded_at, imported_at, status)
                     VALUES (?, ?, 'pc', ?, 1, ?, ?, 'queued')""", (name, name, str(path), recorded, recorded))


def test_unplugged_files_are_skipped_not_failed(tmp_path):
    db.init()
    db.run("DELETE FROM videos")
    gone = _add("old.mp4", tmp_path / "card" / "old.mp4", "2026-01-01 10:00:00")  # oldest, but not there
    here = tmp_path / "new.mp4"
    here.write_bytes(b"x")
    ok = _add("new.mp4", here, "2026-01-02 10:00:00")

    assert worker.next_upload()["id"] == ok
    assert worker.missing_ids() == {gone}

    # Card plugged back in: the oldest goes first again.
    (tmp_path / "card").mkdir()
    (tmp_path / "card" / "old.mp4").write_bytes(b"x")
    assert worker.next_upload()["id"] == gone
    assert worker.missing_ids() == set()


def test_remove_missing_only_drops_unreachable_unuploaded_videos(tmp_path):
    db.init()
    db.run("DELETE FROM videos")
    gone = _add("gone.mp4", tmp_path / "card" / "gone.mp4", "2026-01-01 10:00:00")
    gone_failed = _add("gone2.mp4", tmp_path / "card" / "gone2.mp4", "2026-01-01 11:00:00")
    db.run("UPDATE videos SET status='failed' WHERE id=?", (gone_failed,))
    on_yt = _add("gone3.mp4", tmp_path / "card" / "gone3.mp4", "2026-01-01 12:00:00")
    db.run("UPDATE videos SET status='on_youtube', youtube_id='abc' WHERE id=?", (on_yt,))
    here = tmp_path / "here.mp4"
    here.write_bytes(b"x")
    kept = _add("here.mp4", here, "2026-01-02 10:00:00")

    assert worker.remove_missing([gone, kept]) == 1   # an existing file is never removed
    assert worker.remove_missing() == 1               # the rest: only the failed one qualifies
    left = {r["id"] for r in db.rows("SELECT id FROM videos")}
    assert left == {on_yt, kept}
    assert not db.rows("SELECT 1 FROM tombstones WHERE name IN ('gone.mp4', 'gone2.mp4')")  # can be imported again
