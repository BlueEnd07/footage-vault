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
    conn().executescript(SCHEMA)


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
