import sqlite3
import threading
from datetime import datetime

from .config import DB_PATH

# Status flow:
#   new -> queued -> uploading -> uploaded -> on_youtube -> done
#   new:        needs thumbnail + preview
#   queued:     waiting for YouTube upload
#   uploaded:   on YouTube, waiting for YouTube to finish processing
#   on_youtube: processed; original is kept until delete_after
#   done:       original moved to the Recycle Bin
#   hold:       safety check failed, original kept (see error)
#   failed:     see error; can be retried
SCHEMA = """
CREATE TABLE IF NOT EXISTS videos (
    id           INTEGER PRIMARY KEY,
    fingerprint  TEXT UNIQUE NOT NULL,
    name         TEXT NOT NULL,
    source       TEXT NOT NULL,          -- 'pc' or 'phone'
    path         TEXT NOT NULL,          -- original file on this PC
    size         INTEGER NOT NULL,
    duration     REAL,
    width        INTEGER,
    height       INTEGER,
    recorded_at  TEXT NOT NULL,
    imported_at  TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'new',
    progress     REAL NOT NULL DEFAULT 0,
    attempts     INTEGER NOT NULL DEFAULT 0,
    error        TEXT,
    youtube_id   TEXT,
    processed_at TEXT,
    delete_after TEXT,
    deleted_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_videos_status ON videos(status);
CREATE INDEX IF NOT EXISTS idx_videos_recorded ON videos(recorded_at);

-- Albums mirror private YouTube playlists. Changes are made here first and
-- synced by the worker (title_synced / item_id / removed / deleted).
CREATE TABLE IF NOT EXISTS albums (
    id           INTEGER PRIMARY KEY,
    name         TEXT NOT NULL,
    playlist_id  TEXT,
    title_synced INTEGER NOT NULL DEFAULT 0,
    deleted      INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS album_videos (
    album_id INTEGER NOT NULL,
    video_id INTEGER NOT NULL,
    item_id  TEXT,                       -- YouTube playlistItem id once synced
    removed  INTEGER NOT NULL DEFAULT 0,
    added_at TEXT NOT NULL,
    PRIMARY KEY (album_id, video_id)
);

-- Videos deleted forever: their fingerprint stays here so a copy still on the
-- phone/PC isn't imported and uploaded again.
CREATE TABLE IF NOT EXISTS tombstones (
    fingerprint TEXT PRIMARY KEY,
    name        TEXT,
    deleted_at  TEXT NOT NULL
);

-- Web Push subscriptions (one per phone/browser that turned notifications on)
CREATE TABLE IF NOT EXISTS push_subs (
    endpoint   TEXT PRIMARY KEY,
    data       TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""

_local = threading.local()


def conn() -> sqlite3.Connection:
    c = getattr(_local, "c", None)
    if c is None:
        c = sqlite3.connect(DB_PATH, timeout=30)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        _local.c = c
    return c


def init() -> None:
    c = conn()
    c.executescript(SCHEMA)
    cols = {r["name"] for r in c.execute("PRAGMA table_info(videos)")}
    if "sync_path" not in cols:
        # The file in a phone-sync folder; the vault works on its own linked copy in `path`.
        c.execute("ALTER TABLE videos ADD COLUMN sync_path TEXT")
    if "phone_cleared_at" not in cols:
        # Set once the phone's copy is gone (Free up space, deleted on the phone, or marked by hand).
        c.execute("ALTER TABLE videos ADD COLUMN phone_cleared_at TEXT")
    if "lat" not in cols:
        # GPS from the file (if the camera saved it) and the nearest town, looked up offline.
        c.execute("ALTER TABLE videos ADD COLUMN lat REAL")
        c.execute("ALTER TABLE videos ADD COLUMN lon REAL")
        c.execute("ALTER TABLE videos ADD COLUMN place TEXT")
        c.execute("ALTER TABLE videos ADD COLUMN geo_checked INTEGER NOT NULL DEFAULT 0")
    c.commit()


def rows(sql: str, args=()) -> list[dict]:
    return [dict(r) for r in conn().execute(sql, args).fetchall()]


def row(sql: str, args=()) -> dict | None:
    r = conn().execute(sql, args).fetchone()
    return dict(r) if r else None


def run(sql: str, args=()) -> int:
    c = conn()
    cur = c.execute(sql, args)
    c.commit()
    return cur.lastrowid


def now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")
