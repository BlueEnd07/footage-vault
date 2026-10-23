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
    upload_url   TEXT,                  -- resumable session, so a restart continues the upload
    upload_offset INTEGER NOT NULL DEFAULT 0,
    processed_at TEXT,
    delete_after TEXT,
    deleted_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_videos_status ON videos(status);
CREATE INDEX IF NOT EXISTS idx_videos_recorded ON videos(recorded_at);
CREATE INDEX IF NOT EXISTS idx_videos_youtube ON videos(youtube_id);

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

-- Face identification (all on this PC, see faces.py). One row per person per clip;
-- persons are groups of faces of the same person (named, merged or hidden by the user).
CREATE TABLE IF NOT EXISTS persons (
    id         INTEGER PRIMARY KEY,
    name       TEXT,
    cover_face INTEGER,              -- faces.id shown as the person's picture
    hidden     INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS faces (
    id        INTEGER PRIMARY KEY,
    video_id  INTEGER NOT NULL,
    person_id INTEGER,
    t         REAL,                 -- seconds into the clip (best view of the face)
    score     REAL,                 -- detection confidence x sharpness
    size      INTEGER,              -- face width in the 720p preview, px
    embedding BLOB NOT NULL         -- 128 x float32, L2-normalised
);
CREATE INDEX IF NOT EXISTS idx_faces_person ON faces(person_id);
CREATE INDEX IF NOT EXISTS idx_faces_video ON faces(video_id);

-- Web Push subscriptions (one per phone/browser that turned notifications on)
CREATE TABLE IF NOT EXISTS push_subs (
    endpoint   TEXT PRIMARY KEY,
    data       TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""

_local = threading.local()
_revision = 0
_rev_lock = threading.Lock()


def revision() -> int:
    """Cheap change counter for the browser's render cache (this process only)."""
    return _revision


def conn() -> sqlite3.Connection:
    c = getattr(_local, "c", None)
    if c is None:
        c = sqlite3.connect(DB_PATH, timeout=30)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA synchronous=NORMAL")
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
    if "faces_checked" not in cols:
        # 1 once faces.py has looked for faces in the preview
        c.execute("ALTER TABLE videos ADD COLUMN faces_checked INTEGER NOT NULL DEFAULT 0")
    if "size" not in {r["name"] for r in c.execute("PRAGMA table_info(faces)")}:
        c.execute("ALTER TABLE faces ADD COLUMN size INTEGER")
    if "favorite" not in cols:
        c.execute("ALTER TABLE videos ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
    if "privacy" not in cols:
        # For videos found on YouTube (uploaded outside the vault): private / unlisted / public
        c.execute("ALTER TABLE videos ADD COLUMN privacy TEXT")
    if "upload_url" not in cols:
        # Resumable upload session, kept across restarts and quota pauses.
        c.execute("ALTER TABLE videos ADD COLUMN upload_url TEXT")
        c.execute("ALTER TABLE videos ADD COLUMN upload_offset INTEGER NOT NULL DEFAULT 0")
    c.commit()


def rows(sql: str, args=()) -> list[dict]:
    return [dict(r) for r in conn().execute(sql, args).fetchall()]


def row(sql: str, args=()) -> dict | None:
    r = conn().execute(sql, args).fetchone()
    return dict(r) if r else None


def run(sql: str, args=()) -> int:
    global _revision
    c = conn()
    cur = c.execute(sql, args)
    c.commit()
    with _rev_lock:
        _revision += 1
    return cur.lastrowid


def now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")
