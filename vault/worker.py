"""Background jobs: previews, YouTube uploads, processing checks, safe delete."""
import logging
import sqlite3
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from send2trash import send2trash

from . import db, media, youtube
from .config import DATA, PREVIEWS, THUMBS, load_settings

log = logging.getLogger("vault")

state = {
    "paused_until": None,   # set when the daily quota runs out
    "pause_reason": None,
    "import": None,         # {"folder", "found", "added", "skipped", "running"}
    "login_running": False,
}
_import_lock = threading.Lock()


# ---------- registering files ----------

def register(path: Path, source: str, fallback_time: datetime | None = None,
             name: str | None = None) -> int | None:
    """Adds a video to the library. Returns its id, or None if it's a duplicate."""
    fp = media.fingerprint(path)
    if db.row("SELECT id FROM videos WHERE fingerprint=?", (fp,)):
        return None
    meta = media.probe(path)
    recorded = meta["recorded"] or fallback_time or datetime.fromtimestamp(path.stat().st_mtime)
    try:
        return db.run(
            """INSERT INTO videos (fingerprint, name, source, path, size, duration, width, height,
                                   recorded_at, imported_at)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (fp, name or path.name, source, str(path), path.stat().st_size, meta["duration"],
             meta["width"], meta["height"], recorded.strftime("%Y-%m-%d %H:%M:%S"), db.now()))
    except sqlite3.IntegrityError:
        return None


def import_folder(folder: str) -> None:
    root = Path(folder)
    with _import_lock:
        info = state["import"] = {"folder": str(root), "found": 0, "added": 0,
                                  "skipped": 0, "errors": 0, "running": True}
        try:
            for p in root.rglob("*"):
                if p.suffix.lower() not in media.VIDEO_EXTS or not p.is_file():
                    continue
                if DATA in p.parents:
                    continue
                info["found"] += 1
                try:
                    if register(p, "pc"):
                        info["added"] += 1
                    else:
                        info["skipped"] += 1
                except Exception as e:  # unreadable / not really a video
                    log.warning("Import skipped %s: %s", p, e)
                    info["errors"] += 1
        finally:
            info["running"] = False


# ---------- loops ----------

def _media_loop():
    while True:
        v = db.row("SELECT * FROM videos WHERE status='new' ORDER BY id LIMIT 1")
        if not v:
            time.sleep(2)
            continue
        try:
            src = Path(v["path"])
            media.make_thumb(src, THUMBS / f"{v['id']}.jpg", v["duration"] or 0)
            media.make_preview(src, PREVIEWS / f"{v['id']}.mp4")
            db.run("UPDATE videos SET status='queued', error=NULL WHERE id=?", (v["id"],))
        except Exception as e:
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?", (str(e)[:500], v["id"]))


def _next_quota_reset() -> datetime:
    """YouTube API quota resets at midnight Pacific time."""
    pt = ZoneInfo("America/Los_Angeles")
    tomorrow = (datetime.now(pt) + timedelta(days=1)).replace(hour=0, minute=5, second=0, microsecond=0)
    return tomorrow.astimezone().replace(tzinfo=None)


def _upload_one(v: dict):
    db.run("UPDATE videos SET status='uploading', progress=0, error=NULL WHERE id=?", (v["id"],))
    recorded = datetime.strptime(v["recorded_at"], "%Y-%m-%d %H:%M:%S")
    title = f"{recorded:%Y-%m-%d %H:%M} · {Path(v['name']).stem}"
    title = title.replace("<", "").replace(">", "")[:100]
    last = [0.0]

    def progress(p):
        if p - last[0] >= 0.01:
            last[0] = p
            db.run("UPDATE videos SET progress=? WHERE id=?", (p, v["id"]))

    try:
        vid = youtube.upload(v["path"], title, f"Original file: {v['name']}",
                             recorded.astimezone().isoformat(), progress)
        db.run("UPDATE videos SET status='uploaded', youtube_id=?, progress=1 WHERE id=?",
               (vid, v["id"]))
    except youtube.QuotaExceeded:
        db.run("UPDATE videos SET status='queued', progress=0 WHERE id=?", (v["id"],))
        state["paused_until"] = _next_quota_reset()
        state["pause_reason"] = "YouTube daily upload limit reached"
    except Exception as e:
        attempts = v["attempts"] + 1
        status = "failed" if attempts >= 3 else "queued"
        db.run("UPDATE videos SET status=?, attempts=?, progress=0, error=? WHERE id=?",
               (status, attempts, str(e)[:500], v["id"]))
        time.sleep(30)


def _check_youtube():
    """Moves uploaded videos forward once YouTube has processed them."""
    waiting = db.rows("SELECT id, youtube_id FROM videos WHERE status='uploaded'")
    if not waiting:
        return
    states = youtube.video_states([w["youtube_id"] for w in waiting])
    days = load_settings()["delete_after_days"]
    for w in waiting:
        s = states.get(w["youtube_id"])
        if s is None:
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?",
                   ("Video no longer exists on YouTube", w["id"]))
        elif s["upload_status"] == "processed":
            delete_after = (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")
            db.run("UPDATE videos SET status='on_youtube', processed_at=?, delete_after=? WHERE id=?",
                   (db.now(), delete_after, w["id"]))
        elif s["upload_status"] in ("failed", "rejected", "deleted"):
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?",
                   (f"YouTube {s['upload_status']}: {s['failure']}", w["id"]))


def _safe_delete_due():
    due = db.rows("SELECT * FROM videos WHERE status='on_youtube' AND delete_after <= ?", (db.now(),))
    if not due:
        return
    states = youtube.video_states([d["youtube_id"] for d in due])
    for v in due:
        hold = None
        s = states.get(v["youtube_id"])
        path = Path(v["path"])
        if not s or s["upload_status"] != "processed":
            hold = "YouTube copy is missing or not processed"
        elif abs(s["duration"] - (v["duration"] or 0)) > max(2.0, (v["duration"] or 0) * 0.01):
            hold = f"Length mismatch (YouTube {s['duration']:.0f}s vs file {v['duration']:.0f}s)"
        elif path.exists() and path.stat().st_size != v["size"]:
            hold = "Original file changed since import"
        if hold:
            db.run("UPDATE videos SET status='hold', error=? WHERE id=?", (hold, v["id"]))
            continue
        if path.exists():
            send2trash(str(path))  # Recycle Bin, not permanent delete
        db.run("UPDATE videos SET status='done', deleted_at=? WHERE id=?", (db.now(), v["id"]))


def _youtube_loop():
    last_check = 0.0
    while True:
        try:
            if not youtube.credentials():
                time.sleep(5)
                continue
            if time.time() - last_check > 60:
                last_check = time.time()
                _check_youtube()
                _safe_delete_due()
            if state["paused_until"] and datetime.now() < state["paused_until"]:
                time.sleep(30)
                continue
            state["paused_until"] = state["pause_reason"] = None
            v = db.row("SELECT * FROM videos WHERE status='queued' ORDER BY recorded_at LIMIT 1")
            if v:
                _upload_one(v)
            else:
                time.sleep(3)
        except Exception:
            log.exception("YouTube loop error")
            time.sleep(30)


def start():
    db.init()
    # An upload interrupted by closing the app starts again from the beginning.
    db.run("UPDATE videos SET status='queued', progress=0 WHERE status='uploading'")
    for fn in (_media_loop, _youtube_loop):
        threading.Thread(target=fn, daemon=True, name=fn.__name__).start()
