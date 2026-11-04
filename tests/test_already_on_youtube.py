"""Videos already on the channel (same file name + length, and size/day when known) aren't uploaded again."""
from vault import db, worker


def _local(name, duration, size=1000, recorded="2026-10-17 17:55:00", status="queued"):
    return db.run("""INSERT INTO videos (fingerprint, name, source, path, size, duration, recorded_at, imported_at, status)
                     VALUES (?, ?, 'pc', ?, ?, ?, ?, '2026-11-01 00:00:00', ?)""",
                  (f"fp-{name}-{size}-{recorded}", name, f"C:/v/{name}", size, duration, recorded, status))


def _yt_only(yt_id, title, duration):
    return db.run("""INSERT INTO videos (fingerprint, name, source, path, size, duration, recorded_at, imported_at,
                                         status, youtube_id)
                     VALUES (?, ?, 'youtube', '', 0, ?, '2026-10-17 17:55:00', '2026-11-01 00:00:00', 'youtube', ?)""",
                  ("yt:" + yt_id, title, duration, yt_id))


def _get(vid):
    return db.row("SELECT * FROM videos WHERE id=?", (vid,))


def setup_function():
    db.init()
    for t in ("album_videos", "videos"):
        db.run(f"DELETE FROM {t}")


def test_phone_upload_in_description_is_matched():
    v = _local("VID_20261017_175500.mp4", 30.0)
    n = worker.match_local([{"id": "abc", "title": "2026-10-17 17:55 · VID_20261017_175500", "duration": 30.4,
                             "description": 'Original file: VID_20261017_175500.mp4\n\nfv:{"n":"VID_20261017_175500.mp4","s":1000}'}])
    assert n == 1
    row = _get(v)
    assert row["youtube_id"] == "abc" and row["status"] == "new"  # no preview yet: preview first, then backed up


def test_different_length_is_not_a_match():
    v = _local("DJI_0001.MP4", 30.0)
    assert worker.match_local([{"id": "abc", "title": "DJI_0001", "description": "", "duration": 95.0}]) == 0
    assert _get(v)["youtube_id"] is None


def test_same_name_and_length_but_other_size_or_day_is_not_a_match():
    v = _local("DJI_0001.MP4", 30.0, size=1000, recorded="2026-10-17 17:55:00")
    other_size = {"id": "a", "title": "x · DJI_0001", "duration": 30.0, "description": 'fv:{"n":"DJI_0001.MP4","s":2222}'}
    other_day = {"id": "b", "title": "2026-03-02 10:00 · DJI_0001", "duration": 30.0, "description": ""}
    assert worker.match_local([other_size, other_day]) == 0
    assert _get(v)["youtube_id"] is None


def test_youtube_only_row_is_merged_before_upload():
    yt = _yt_only("xyz", "2026-10-17 17:55 · DJI_0042", 12.0)
    db.run("INSERT INTO albums (id, name, created_at) VALUES (1, 'Trip', '2026-11-01 00:00:00')")
    db.run("INSERT INTO album_videos (album_id, video_id, added_at) VALUES (1, ?, '2026-11-01 00:00:00')", (yt,))
    v = _local("DJI_0042.MP4", 12.5)
    assert worker._already_on_youtube(_get(v))
    assert _get(yt) is None
    assert _get(v)["youtube_id"] == "xyz"
    assert db.row("SELECT video_id FROM album_videos WHERE album_id=1")["video_id"] == v


def test_unrelated_video_still_uploads():
    _yt_only("xyz", "2026-10-17 17:55 · DJI_0042", 12.0)
    v = _local("DJI_0043.MP4", 12.0)
    assert not worker._already_on_youtube(_get(v))
    assert _get(v)["status"] == "queued"
