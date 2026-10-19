"""Background jobs: previews, YouTube uploads, processing checks, safe delete,
watched folders and album/playlist sync."""
import ctypes
import logging
import os
import shutil
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from send2trash import send2trash

from . import db, media, notify, youtube
from .config import DATA, INCOMING, PREVIEWS, THUMBS, load_settings

log = logging.getLogger("vault")

state = {
    "paused_until": None,   # set when the daily quota runs out
    "pause_reason": None,
    "user_paused": False,   # "Pause uploads" in the tray menu
    "import": None,         # {"folder", "found", "added", "skipped", "running"}
    "albums_paused_until": 0.0,
}
_import_lock = threading.Lock()


def keep_awake(on: bool) -> None:
    """Stops Windows from sleeping while this thread is busy (uploads / previews)."""
    if os.name == "nt":
        ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if on else 0))


# ---------- registering files ----------

def register(path: Path, source: str, fallback_time: datetime | None = None,
             name: str | None = None, sync_path: Path | None = None) -> int | None:
    """Adds a video to the library. Returns its id, or None if it's a duplicate."""
    fp = media.fingerprint(path)
    if db.row("SELECT id FROM videos WHERE fingerprint=?", (fp,)):
        return None
    meta = media.probe(path)
    recorded = meta["recorded"] or fallback_time or datetime.fromtimestamp(path.stat().st_mtime)
    try:
        return db.run(
            """INSERT INTO videos (fingerprint, name, source, path, size, duration, width, height,
                                   recorded_at, imported_at, sync_path)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (fp, name or path.name, source, str(path), path.stat().st_size, meta["duration"],
             meta["width"], meta["height"], recorded.strftime("%Y-%m-%d %H:%M:%S"), db.now(),
             str(sync_path) if sync_path else None))
    except sqlite3.IntegrityError:
        return None


def _register_synced(p: Path) -> int | None:
    """A file in a phone-sync folder. The sync app deletes it here when it's deleted on the
    phone, so the vault keeps its own hard link (or copy) in data/incoming to work on."""
    if db.row("SELECT id FROM videos WHERE fingerprint=?", (media.fingerprint(p),)):
        return None
    dest = INCOMING / f"{uuid.uuid4().hex[:8]}_{p.name}"
    try:
        os.link(p, dest)  # same drive: no extra space used
    except OSError:
        shutil.copy2(p, dest)
    try:
        vid = register(dest, "phone", datetime.fromtimestamp(p.stat().st_mtime), p.name, sync_path=p)
    except Exception:
        dest.unlink(missing_ok=True)
        raise
    if vid is None:
        dest.unlink(missing_ok=True)
    return vid


def _video_files(root: Path):
    """Video files under root, skipping hidden/temp files and sync-app folders (.stfolder etc.)."""
    for p in root.rglob("*"):
        if p.suffix.lower() not in media.VIDEO_EXTS or p.name.startswith(("~", ".")):
            continue
        rel = p.relative_to(root).parts
        if any(part.startswith(".") for part in rel) or DATA in p.parents or not p.is_file():
            continue
        yield p


def import_folder(folder: str) -> None:
    root = Path(folder)
    with _import_lock:
        info = state["import"] = {"folder": str(root), "found": 0, "added": 0,
                                  "skipped": 0, "errors": 0, "running": True}
        try:
            for p in _video_files(root):
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


# ---------- watched folders (e.g. the folder Syncthing copies phone videos into) ----------

_watch_seen: dict[str, tuple] = {}      # path -> (size, mtime) at last scan
_watch_ignored: dict[str, tuple] = {}   # duplicates / unreadable, until the file changes


def _mark_deleted_on_phone(roots: list[Path]) -> None:
    """A synced file that vanished from a (present) sync folder was deleted on the phone."""
    for r in db.rows("SELECT id, sync_path FROM videos WHERE sync_path IS NOT NULL AND phone_cleared_at IS NULL"):
        sp = Path(r["sync_path"])
        if not sp.exists() and any(root in sp.parents for root in roots):
            db.run("UPDATE videos SET phone_cleared_at=? WHERE id=?", (db.now(), r["id"]))


def _scan_watch_folders() -> None:
    folders = load_settings().get("watch_folders") or []
    if not folders:
        return
    _mark_deleted_on_phone([Path(f["path"]) for f in folders if Path(f["path"]).is_dir()])
    known = set()
    for r in db.rows("SELECT path, sync_path FROM videos"):
        known.update(p for p in (r["path"], r["sync_path"]) if p)
    for wf in folders:
        root = Path(wf["path"])
        if not root.is_dir():
            continue
        added = 0
        for p in _video_files(root):
            key = str(p)
            if key in known:
                continue
            try:
                st = p.stat()
            except OSError:
                continue
            sig = (st.st_size, st.st_mtime)
            if _watch_ignored.get(key) == sig:
                continue
            # Only import once the file stopped changing (sync finished).
            if _watch_seen.get(key) != sig or time.time() - st.st_mtime < 10:
                _watch_seen[key] = sig
                continue
            _watch_seen.pop(key, None)
            try:
                phone = wf.get("source", "phone") == "phone"
                if _register_synced(p) if phone else register(p, "pc"):
                    added += 1
                else:
                    _watch_ignored[key] = sig
            except Exception as e:
                log.warning("Watch import skipped %s: %s", p, e)
                _watch_ignored[key] = sig
        if added and wf.get("source", "phone") == "phone":
            notify.event("received_phone", added)


def _watch_loop():
    while True:
        try:
            _scan_watch_folders()
        except Exception:
            log.exception("Watch folder scan failed")
        time.sleep(15)


# ---------- previews ----------

def _media_loop():
    while True:
        v = db.row("SELECT * FROM videos WHERE status='new' ORDER BY id LIMIT 1")
        if not v:
            time.sleep(2)
            continue
        keep_awake(True)
        try:
            src = Path(v["path"])
            media.make_thumb(src, THUMBS / f"{v['id']}.jpg", v["duration"] or 0)
            media.make_preview(src, PREVIEWS / f"{v['id']}.mp4")
            db.run("UPDATE videos SET status='queued', error=NULL WHERE id=?", (v["id"],))
        except Exception as e:
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?", (str(e)[:500], v["id"]))
            notify.event("failed")
        finally:
            keep_awake(False)


# ---------- uploads ----------

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

    keep_awake(True)
    try:
        vid = youtube.upload(v["path"], title, f"Original file: {v['name']}",
                             recorded.astimezone().isoformat(), progress)
        db.run("UPDATE videos SET status='uploaded', youtube_id=?, progress=1 WHERE id=?",
               (vid, v["id"]))
        notify.clear_alert("quota")
    except youtube.QuotaExceeded:
        db.run("UPDATE videos SET status='queued', progress=0 WHERE id=?", (v["id"],))
        state["paused_until"] = _next_quota_reset()
        state["pause_reason"] = "YouTube daily upload limit reached"
        notify.alert_once("quota", "YouTube's daily upload limit was reached. "
                          f"Uploads continue automatically at {state['paused_until']:%H:%M}.")
    except Exception as e:
        attempts = v["attempts"] + 1
        status = "failed" if attempts >= 3 else "queued"
        db.run("UPDATE videos SET status=?, attempts=?, progress=0, error=? WHERE id=?",
               (status, attempts, str(e)[:500], v["id"]))
        if status == "failed":
            notify.event("failed")
        time.sleep(30)
    finally:
        keep_awake(False)


def _check_youtube():
    """Moves uploaded videos forward once YouTube has processed them."""
    waiting = db.rows("SELECT id, youtube_id, source FROM videos WHERE status='uploaded'")
    if not waiting:
        return
    states = youtube.video_states([w["youtube_id"] for w in waiting])
    days = load_settings()["delete_after_days"]
    for w in waiting:
        s = states.get(w["youtube_id"])
        if s is None:
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?",
                   ("Video no longer exists on YouTube", w["id"]))
            notify.event("failed")
        elif s["upload_status"] == "processed":
            delete_after = (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")
            db.run("UPDATE videos SET status='on_youtube', processed_at=?, delete_after=? WHERE id=?",
                   (db.now(), delete_after, w["id"]))
            notify.event("safe_phone" if w["source"] == "phone" else "backed_up_pc")
        elif s["upload_status"] in ("failed", "rejected", "deleted"):
            db.run("UPDATE videos SET status='failed', error=? WHERE id=?",
                   (f"YouTube {s['upload_status']}: {s['failure']}", w["id"]))
            notify.event("failed")


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
            notify.event("failed")
            continue
        # Only the vault's own copy. The copy in a phone-sync folder is left alone:
        # deleting it would delete the video on the phone, which only "Free up space" does.
        if path.exists():
            send2trash(str(path))  # Recycle Bin, not permanent delete
        db.run("UPDATE videos SET status='done', deleted_at=? WHERE id=?", (db.now(), v["id"]))


# ---------- free up space on the phone ----------

def free_space_candidates() -> dict:
    """Phone videos that are safely on YouTube but may still be on the phone."""
    rows = db.rows("""SELECT * FROM videos WHERE source='phone' AND status IN ('on_youtube','done')
                      AND phone_cleared_at IS NULL ORDER BY recorded_at""")
    synced = [r for r in rows if r["sync_path"] and Path(r["sync_path"]).exists()]
    manual = [r for r in rows if not (r["sync_path"] and Path(r["sync_path"]).exists())]
    return {"synced": synced, "manual": manual}


def free_space() -> dict:
    """Deletes synced phone videos from the PC's sync folder. Syncthing (Send & Receive)
    then deletes them on the phone at its next Wi-Fi sync. Every video is re-checked
    against YouTube first."""
    synced = free_space_candidates()["synced"]
    states = youtube.video_states([v["youtube_id"] for v in synced]) if synced else {}
    freed, size, kept = 0, 0, []
    for v in synced:
        s = states.get(v["youtube_id"])
        ok = s and s["upload_status"] == "processed" and \
            abs(s["duration"] - (v["duration"] or 0)) <= max(2.0, (v["duration"] or 0) * 0.01)
        sync = Path(v["sync_path"])
        if not ok or sync.stat().st_size != v["size"]:
            kept.append(v["name"])
            continue
        send2trash(str(sync))
        own = Path(v["path"])
        if own.exists():
            send2trash(str(own))
        db.run("""UPDATE videos SET status='done', deleted_at=COALESCE(deleted_at, ?), phone_cleared_at=?
                  WHERE id=?""", (db.now(), db.now(), v["id"]))
        freed += 1
        size += v["size"]
    return {"freed": freed, "bytes": size, "kept": kept}


def mark_phone_cleared(ids: list[int]) -> None:
    for vid in ids:
        db.run("UPDATE videos SET phone_cleared_at=? WHERE id=? AND source='phone' AND status IN ('on_youtube','done')",
               (db.now(), vid))


# ---------- albums -> YouTube playlists ----------

def _sync_albums():
    if time.time() < state["albums_paused_until"]:
        return
    try:
        for a in db.rows("SELECT * FROM albums WHERE deleted=1"):
            if a["playlist_id"]:
                try:
                    youtube.delete_playlist(a["playlist_id"])
                except Exception as e:
                    if not youtube.is_not_found(e):
                        raise
            db.run("DELETE FROM album_videos WHERE album_id=?", (a["id"],))
            db.run("DELETE FROM albums WHERE id=?", (a["id"],))

        for a in db.rows("SELECT * FROM albums WHERE deleted=0 AND (playlist_id IS NULL OR title_synced=0)"):
            if a["playlist_id"]:
                try:
                    youtube.rename_playlist(a["playlist_id"], a["name"])
                    db.run("UPDATE albums SET title_synced=1 WHERE id=?", (a["id"],))
                    continue
                except Exception as e:
                    if not youtube.is_not_found(e):
                        raise
                    # Playlist was deleted on YouTube: make a new one below.
            pid = youtube.create_playlist(a["name"])
            db.run("UPDATE albums SET playlist_id=?, title_synced=1 WHERE id=?", (pid, a["id"]))
            db.run("UPDATE album_videos SET item_id=NULL WHERE album_id=?", (a["id"],))

        for r in db.rows("SELECT album_id, video_id, item_id FROM album_videos WHERE removed=1"):
            if r["item_id"]:
                try:
                    youtube.remove_from_playlist(r["item_id"])
                except Exception as e:
                    if not youtube.is_not_found(e):
                        raise
            db.run("DELETE FROM album_videos WHERE album_id=? AND video_id=?", (r["album_id"], r["video_id"]))

        pending = db.rows(
            """SELECT av.album_id, av.video_id, a.playlist_id, v.youtube_id
               FROM album_videos av JOIN albums a ON a.id = av.album_id JOIN videos v ON v.id = av.video_id
               WHERE av.removed=0 AND av.item_id IS NULL AND a.deleted=0 AND a.playlist_id IS NOT NULL
                 AND v.youtube_id IS NOT NULL AND v.status IN ('uploaded','on_youtube','done','hold')""")
        for r in pending:
            item = youtube.add_to_playlist(r["playlist_id"], r["youtube_id"])
            db.run("UPDATE album_videos SET item_id=? WHERE album_id=? AND video_id=?",
                   (item, r["album_id"], r["video_id"]))
    except youtube.QuotaExceeded:
        state["albums_paused_until"] = time.time() + 3600
    except Exception:
        log.exception("Album sync failed")
        state["albums_paused_until"] = time.time() + 300


# ---------- main YouTube loop ----------

def _youtube_loop():
    last_check = 0.0
    while True:
        try:
            if not youtube.credentials():
                auth = youtube.auth_state()
                if auth in ("expired", "scope"):
                    notify.alert_once("login", "YouTube got disconnected, so uploads are paused. "
                                      "Open Footage Vault on the PC and click Connect YouTube.")
                time.sleep(10)
                continue
            notify.clear_alert("login")
            if time.time() - last_check > 60:
                last_check = time.time()
                _check_youtube()
                _safe_delete_due()
            _sync_albums()
            if state["user_paused"]:
                time.sleep(3)
                continue
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
    notify.start()
    for fn in (_media_loop, _youtube_loop, _watch_loop):
        threading.Thread(target=fn, daemon=True, name=fn.__name__).start()
