"""Background jobs: previews, YouTube uploads, processing checks, safe delete,
watched folders and album/playlist sync."""
import ctypes
import logging
import os
import re
import shutil
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from send2trash import send2trash

from . import db, faces, media, notify, timeguess, youtube
from .config import DATA, INCOMING, PREVIEWS, THUMBS, load_settings

log = logging.getLogger("vault")

state = {
    "paused_until": None,   # set when the daily quota runs out
    "pause_reason": None,
    "user_paused": False,   # "Pause uploads" in the tray menu
    "import": None,         # {"folder", "found", "added", "skipped", "running"}
    "albums_paused_until": 0.0,
    "takeout": None,         # progress of "Import YouTube Takeout"
    "yt_import": None,       # result of the last pull from YouTube
    "yt_import_now": False,  # "Sync from YouTube" button
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
    """Adds a video to the library. Returns its id, or None if it's a duplicate
    (or a video that was deleted forever)."""
    fp = media.fingerprint(path)
    if db.row("SELECT id FROM videos WHERE fingerprint=?", (fp,)) or \
            db.row("SELECT 1 FROM tombstones WHERE fingerprint=?", (fp,)):
        return None
    meta = media.probe(path)
    guess = timeguess.from_name(name or path.name)
    recorded = meta["recorded"] or (guess[0] if guess else None) or fallback_time or datetime.fromtimestamp(path.stat().st_mtime)
    place = media.place_name(meta["lat"], meta["lon"]) if meta["lat"] is not None else None
    try:
        return db.run(
            """INSERT INTO videos (fingerprint, name, source, path, size, duration, width, height,
                                   recorded_at, imported_at, sync_path, lat, lon, place, geo_checked)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)""",
            (fp, name or path.name, source, str(path), path.stat().st_size, meta["duration"],
             meta["width"], meta["height"], recorded.strftime("%Y-%m-%d %H:%M:%S"), db.now(),
             str(sync_path) if sync_path else None, meta["lat"], meta["lon"], place))
    except sqlite3.IntegrityError:
        return None


def _register_synced(p: Path) -> int | None:
    """A file in a phone-sync folder. The sync app deletes it here when it's deleted on the
    phone, so the vault keeps its own hard link (or copy) in data/incoming to work on."""
    fp = media.fingerprint(p)
    if db.row("SELECT 1 FROM tombstones WHERE fingerprint=?", (fp,)):
        return None  # deleted forever; don't bring it back
    existing = db.row("SELECT id, source, sync_path FROM videos WHERE fingerprint=?", (fp,))
    if existing:
        # Same video sent earlier with ＋: remember where its phone copy now syncs to,
        # so "Free up space" can delete it from the phone with one click.
        if existing["source"] == "phone" and not existing["sync_path"]:
            db.run("UPDATE videos SET sync_path=?, phone_cleared_at=NULL WHERE id=?", (str(p), existing["id"]))
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


def _file_ready(path: Path, wait: float = 2.0) -> bool:
    """False while a file is still being written (fresh mtime that keeps changing)."""
    try:
        before = path.stat()
    except OSError:
        return False
    if time.time() - before.st_mtime >= 10:
        return True
    time.sleep(wait)
    try:
        after = path.stat()
    except OSError:
        return False
    return (before.st_size, before.st_mtime) == (after.st_size, after.st_mtime)


def import_folder(folder: str) -> None:
    root = Path(folder)
    with _import_lock:
        info = state["import"] = {"folder": str(root), "found": 0, "added": 0,
                                  "skipped": 0, "errors": 0, "running": True}
        try:
            for p in _video_files(root):
                info["found"] += 1
                try:
                    if not _file_ready(p):
                        info["skipped"] += 1
                        continue
                    if register(p, "pc"):
                        info["added"] += 1
                    else:
                        info["skipped"] += 1
                except Exception as e:  # unreadable / not really a video
                    log.warning("Import skipped %s: %s", p, e)
                    info["errors"] += 1
        finally:
            info["running"] = False


# ---------- Google Takeout: local copies of videos that were uploaded to YouTube directly ----------

def _norm_title(s: str) -> str:
    return "".join(ch for ch in s.casefold() if ch.isalnum())


def _takeout_titles(root: Path) -> dict[str, set[str]]:
    """Takeout's 'video metadata' CSV, when included: normalised title -> video ids."""
    import csv
    out: dict[str, set[str]] = {}
    for f in root.rglob("*.csv"):
        try:
            with open(f, encoding="utf-8-sig", newline="") as fh:
                for row in csv.DictReader(fh):
                    vid = row.get("Video ID") or row.get("Video Id")
                    title = row.get("Video Title (Original)") or row.get("Video Title") or row.get("Title")
                    if vid and title:
                        out.setdefault(_norm_title(title), set()).add(vid)
        except Exception:
            continue
    return out


def import_takeout(folder: str) -> None:
    """Matches each video file in an unzipped Google Takeout (YouTube > videos) to a video the
    vault only knows from YouTube, by title and length. Matched videos get a local preview,
    thumbnail, faces and location, and play like any other clip. Nothing is uploaded again."""
    root = Path(folder)
    with _import_lock:
        info = state["takeout"] = {"folder": str(root), "found": 0, "matched": 0, "already": 0,
                                   "unmatched": [], "running": True}
        try:
            by_csv = _takeout_titles(root)
            waiting = db.rows("SELECT id, name, duration, youtube_id FROM videos WHERE status='youtube'")
            for p in _video_files(root):
                info["found"] += 1
                try:
                    meta = media.probe(p)
                    fp = media.fingerprint(p)
                except Exception:
                    info["unmatched"].append(p.name)
                    continue
                if db.row("SELECT 1 FROM videos WHERE fingerprint=?", (fp,)):
                    info["already"] += 1  # e.g. a vault upload, already has its own copy
                    continue
                # Takeout names files after the title; "(1)", "(2)"… when titles repeat.
                stem = _norm_title(re.sub(r"\(\d+\)$", "", p.stem.strip()))
                ids = by_csv.get(stem, set())
                cands = [w for w in waiting if w["youtube_id"] in ids or _norm_title(w["name"]) == stem]
                # Same title can mean several videos: the length decides (closest wins).
                close = sorted((w for w in cands if abs((w["duration"] or 0) - meta["duration"]) <= max(2.0, meta["duration"] * 0.01)),
                               key=lambda w: abs((w["duration"] or 0) - meta["duration"]))
                pick = close[0] if close else None
                if not pick:
                    info["unmatched"].append(p.name)
                    continue
                waiting.remove(pick)
                recorded = meta["recorded"].strftime("%Y-%m-%d %H:%M:%S") if meta["recorded"] else None
                place = media.place_name(meta["lat"], meta["lon"]) if meta["lat"] is not None else None
                db.run("""UPDATE videos SET fingerprint=?, path=?, size=?, width=?, height=?, duration=?,
                                 recorded_at=COALESCE(?, recorded_at), lat=?, lon=?, place=?, geo_checked=1,
                                 faces_checked=0, status='new', error=NULL
                          WHERE id=? AND status='youtube'""",
                       (fp, str(p), p.stat().st_size, meta["width"], meta["height"], meta["duration"],
                        recorded, meta["lat"], meta["lon"], place, pick["id"]))
                info["matched"] += 1
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
    for key in list(_watch_seen):
        if not Path(key).exists():
            _watch_seen.pop(key, None)
    for key in list(_watch_ignored):
        if not Path(key).exists():
            _watch_ignored.pop(key, None)


def _watch_loop():
    while True:
        try:
            _scan_watch_folders()
        except Exception:
            log.exception("Watch folder scan failed")
        time.sleep(15)


# ---------- location for videos added before GPS support ----------

def _backfill_locations() -> None:
    for v in db.rows("SELECT id, path, sync_path FROM videos WHERE geo_checked=0"):
        lat = lon = None
        for p in (v["path"], v["sync_path"], str(PREVIEWS / f"{v['id']}.mp4")):
            if p and Path(p).exists():
                try:
                    meta = media.probe(Path(p))
                except Exception:
                    continue
                if meta["lat"] is not None:
                    lat, lon = meta["lat"], meta["lon"]
                    break
        place = media.place_name(lat, lon) if lat is not None else None
        db.run("UPDATE videos SET lat=?, lon=?, place=?, geo_checked=1 WHERE id=?", (lat, lon, place, v["id"]))


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
            if v["youtube_id"]:
                # Already on YouTube (a Takeout copy of a video uploaded elsewhere): don't upload
                # again; the file is treated like an original that's safely backed up.
                days = load_settings()["delete_after_days"]
                delete_after = (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")
                db.run("""UPDATE videos SET status='on_youtube', error=NULL, processed_at=?, delete_after=?
                          WHERE id=?""", (db.now(), delete_after, v["id"]))
            else:
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


def _pause_for_quota() -> None:
    state["paused_until"] = _next_quota_reset()
    state["pause_reason"] = "YouTube daily upload limit reached"
    notify.alert_once("quota", "YouTube's daily upload limit was reached. "
                      f"Uploads continue automatically at {state['paused_until']:%H:%M}.")


def _upload_one(v: dict):
    db.run("UPDATE videos SET status='uploading', error=NULL WHERE id=?", (v["id"],))
    recorded = datetime.strptime(v["recorded_at"], "%Y-%m-%d %H:%M:%S")
    title = f"{recorded:%Y-%m-%d %H:%M} · {Path(v['name']).stem}"
    title = title.replace("<", "").replace(">", "")[:100]
    last = [float(v.get("progress") or 0)]

    def progress(p):
        if p - last[0] >= 0.01:
            last[0] = p
            db.run("UPDATE videos SET progress=? WHERE id=?", (p, v["id"]))

    def save_session(url):
        db.run("UPDATE videos SET upload_url=? WHERE id=?", (url, v["id"]))

    def save_offset(offset):
        db.run("UPDATE videos SET upload_offset=? WHERE id=?", (offset, v["id"]))

    keep_awake(True)
    try:
        vid = youtube.upload(v["path"], title, f"Original file: {v['name']}",
                             recorded.astimezone().isoformat(), progress,
                             session=v.get("upload_url"), offset=int(v.get("upload_offset") or 0),
                             on_session=save_session, on_offset=save_offset)
        db.run("""UPDATE videos SET status='uploaded', youtube_id=?, progress=1,
                  upload_url=NULL, upload_offset=0 WHERE id=?""", (vid, v["id"]))
        notify.clear_alert("quota")
    except youtube.QuotaExceeded:
        # The session stays valid, so the upload picks up where it stopped after the reset.
        db.run("UPDATE videos SET status='queued' WHERE id=?", (v["id"],))
        _pause_for_quota()
    except Exception as e:
        attempts = v["attempts"] + 1
        status = "failed" if attempts >= 3 else "queued"
        clear = ", upload_url=NULL, upload_offset=0" if status == "failed" else ""
        db.run(f"UPDATE videos SET status=?, attempts=?, error=?{clear} WHERE id=?",
               (status, attempts, str(e)[:500], v["id"]))
        if status == "failed":
            notify.event("failed")
        time.sleep(30)
    finally:
        keep_awake(False)


def _check_youtube(states: dict | None = None):
    """Moves uploaded videos forward once YouTube has processed them."""
    waiting = db.rows("SELECT id, youtube_id, source FROM videos WHERE status='uploaded'")
    if not waiting:
        return
    if states is None:
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


def _safe_delete_due(states: dict | None = None):
    due = db.rows("SELECT * FROM videos WHERE status='on_youtube' AND delete_after <= ?", (db.now(),))
    if not due:
        return
    if states is None:
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
        try:
            same_size = sync.stat().st_size == v["size"]
        except OSError:
            same_size = False
        if not ok or not same_size:
            kept.append(v["name"])
            continue
        try:
            send2trash(str(sync))
        except Exception as e:
            log.warning("Could not delete %s: %s", sync, e)
            kept.append(v["name"])
            continue
        own = Path(v["path"])
        try:
            if own.exists():
                send2trash(str(own))
        except Exception as e:  # the phone copy is already gone; keep going
            log.warning("Could not delete %s: %s", own, e)
        db.run("""UPDATE videos SET status='done', deleted_at=COALESCE(deleted_at, ?), phone_cleared_at=?
                  WHERE id=?""", (db.now(), db.now(), v["id"]))
        freed += 1
        size += v["size"]
    return {"freed": freed, "bytes": size, "kept": kept}


def delete_forever(ids: list[int], delete_copies: bool) -> dict:
    """Deletes videos from YouTube (permanent) and from the vault. With delete_copies,
    copies still on this PC go to the Recycle Bin and synced phone copies are removed
    (Syncthing deletes them on the phone)."""
    deleted, skipped = 0, []
    for vid in ids:
        v = db.row("SELECT * FROM videos WHERE id=?", (vid,))
        if not v:
            continue
        if v["status"] == "uploading":
            skipped.append(f"{v['name']} (uploading right now — try again when it's done)")
            continue
        if v["youtube_id"]:
            try:
                youtube.delete_video(v["youtube_id"])
            except youtube.QuotaExceeded:
                skipped.append(f"{v['name']} (YouTube's daily limit reached — try tomorrow)")
                break
            except Exception as e:
                if not youtube.is_not_found(e):  # already gone on YouTube is fine
                    skipped.append(f"{v['name']} ({e})")
                    continue
        if delete_copies:
            for p in (v["path"], v["sync_path"]):
                if p and Path(p).exists():
                    try:
                        send2trash(p)
                    except Exception as e:  # the YouTube copy is already gone; report the copy
                        skipped.append(f"{v['name']} (copy left on disk: {e})")
        elif v["path"] and INCOMING in Path(v["path"]).parents and Path(v["path"]).exists():
            # The vault's own working copy is never the user's original.
            try:
                send2trash(v["path"])
            except Exception as e:
                log.warning("Could not delete %s: %s", v["path"], e)
        for p in (THUMBS / f"{vid}.jpg", PREVIEWS / f"{vid}.mp4"):
            try:
                p.unlink(missing_ok=True)
            except OSError as e:
                log.warning("Could not delete %s: %s", p, e)
        try:
            faces.forget_video(vid)
        except Exception:
            log.exception("Could not forget faces for video %s", vid)
        db.run("INSERT OR REPLACE INTO tombstones (fingerprint, name, deleted_at) VALUES (?,?,?)",
               (v["fingerprint"], v["name"], db.now()))
        db.run("DELETE FROM album_videos WHERE video_id=?", (vid,))  # YouTube drops playlist items itself
        db.run("DELETE FROM videos WHERE id=?", (vid,))
        deleted += 1
    return {"deleted": deleted, "skipped": skipped}


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
                 AND v.youtube_id IS NOT NULL AND v.status IN ('uploaded','on_youtube','done','hold','youtube')""")
        for r in pending:
            item = youtube.add_to_playlist(r["playlist_id"], r["youtube_id"])
            db.run("UPDATE album_videos SET item_id=? WHERE album_id=? AND video_id=?",
                   (item, r["album_id"], r["video_id"]))
    except youtube.QuotaExceeded:
        state["albums_paused_until"] = time.time() + 3600
    except Exception:
        log.exception("Album sync failed")
        state["albums_paused_until"] = time.time() + 300


# ---------- pull from YouTube: videos uploaded elsewhere + playlists as albums ----------

def _yt_time(value: str | None) -> str:
    try:
        dt = datetime.fromisoformat((value or "").replace("Z", "+00:00"))
        return dt.astimezone().replace(tzinfo=None).strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        return db.now()


_MAX_THUMB = 20 * 1024 * 1024


def _download_thumb(url: str | None, vid: int) -> None:
    if not url:
        return
    tmp = THUMBS / f"{vid}.part"
    try:
        import urllib.request
        req = urllib.request.Request(url, headers={"User-Agent": "FootageVault/1.0"})
        total = 0
        with urllib.request.urlopen(req, timeout=15) as r, open(tmp, "wb") as f:
            while True:
                chunk = r.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > _MAX_THUMB:
                    raise ValueError("thumbnail is too large")
                f.write(chunk)
        os.replace(tmp, THUMBS / f"{vid}.jpg")
    except Exception as e:
        tmp.unlink(missing_ok=True)
        log.warning("Thumbnail download failed for %s: %s", vid, e)


def import_from_youtube() -> dict:
    """Adds channel videos the vault doesn't know (uploaded directly to YouTube) and
    mirrors playlists as albums. Cheap on quota: ~1 unit per 50 videos."""
    info = {"added": 0, "removed": 0, "albums": 0}
    ids = youtube.my_upload_ids()
    on_channel = set(ids)
    known = {r["youtube_id"] for r in db.rows("SELECT youtube_id FROM videos WHERE youtube_id IS NOT NULL")}
    gone = {r["fingerprint"][3:] for r in db.rows("SELECT fingerprint FROM tombstones WHERE fingerprint LIKE 'yt:%'")}
    details = youtube.video_details(ids)  # all of them (about 1 quota unit per 50), to re-date as well
    # Re-date videos that only live on YouTube with the best time from their name / details.
    for d in details:
        best = timeguess.for_youtube(d["title"], d["description"], d["recording_date"], d["published"])
        if best:
            db.run("""UPDATE videos SET recorded_at=? WHERE youtube_id=? AND status='youtube'
                      AND ABS(strftime('%s', recorded_at) - strftime('%s', ?)) > 60""",
                   (best.strftime("%Y-%m-%d %H:%M:%S"), d["id"], best.strftime("%Y-%m-%d %H:%M:%S")))
        # A date changed on the phone ("Change date") is saved in the description: apply it to PC copies too.
        if fv := timeguess.from_fv(d["description"]):
            db.run("""UPDATE videos SET recorded_at=? WHERE youtube_id=? AND status!='youtube'
                      AND ABS(strftime('%s', recorded_at) - strftime('%s', ?)) > 60""",
                   (fv.strftime("%Y-%m-%d %H:%M:%S"), d["id"], fv.strftime("%Y-%m-%d %H:%M:%S")))
    new_ids = {i for i in ids if i not in known and i not in gone}
    for d in details:
        if d["id"] not in new_ids or d["upload_status"] not in ("processed", "uploaded"):
            continue
        best = timeguess.for_youtube(d["title"], d["description"], d["recording_date"], d["published"])
        try:
            vid = db.run(
                """INSERT INTO videos (fingerprint, name, source, path, size, duration, recorded_at, imported_at,
                                       status, youtube_id, privacy, geo_checked, faces_checked)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,1,1)""",
                ("yt:" + d["id"], d["title"], "youtube", "", 0, d["duration"],
                 best.strftime("%Y-%m-%d %H:%M:%S") if best else _yt_time(d["recorded"]),
                 db.now(), "youtube", d["id"], d["privacy"]))
        except sqlite3.IntegrityError:
            continue
        _download_thumb(d["thumb"], vid)
        info["added"] += 1
    # Deleted on YouTube directly: drop the vault entry if it has no local copy (one that came
    # in through Takeout keeps its preview, like any other clip).
    for r in db.rows("SELECT id, youtube_id FROM videos WHERE source='youtube' AND status='youtube'"):
        if r["youtube_id"] not in on_channel:
            faces.forget_video(r["id"])
            db.run("DELETE FROM album_videos WHERE video_id=?", (r["id"],))
            db.run("DELETE FROM videos WHERE id=?", (r["id"],))
            (THUMBS / f"{r['id']}.jpg").unlink(missing_ok=True)
            info["removed"] += 1

    by_yt = {r["youtube_id"]: r["id"] for r in db.rows("SELECT id, youtube_id FROM videos WHERE youtube_id IS NOT NULL")}
    playlists = youtube.my_playlists()
    live = {p["id"] for p in playlists}
    for p in playlists:
        a = db.row("SELECT * FROM albums WHERE playlist_id=?", (p["id"],))
        if not a:
            aid = db.run("INSERT INTO albums (name, playlist_id, title_synced, created_at) VALUES (?,?,1,?)",
                         (p["title"], p["id"], db.now()))
            info["albums"] += 1
        else:
            aid = a["id"]
            if a["deleted"]:
                continue
            if a["title_synced"] and a["name"] != p["title"]:  # renamed on YouTube
                db.run("UPDATE albums SET name=? WHERE id=?", (p["title"], aid))
        items = youtube.playlist_items(p["id"])
        item_ids = {i for i, _ in items}
        for item_id, yt_id in items:
            vid = by_yt.get(yt_id)
            if vid is None:
                continue
            row = db.row("SELECT item_id, removed FROM album_videos WHERE album_id=? AND video_id=?", (aid, vid))
            if not row:
                db.run("INSERT INTO album_videos (album_id, video_id, item_id, added_at) VALUES (?,?,?,?)",
                       (aid, vid, item_id, db.now()))
            elif not row["item_id"] and not row["removed"]:
                db.run("UPDATE album_videos SET item_id=? WHERE album_id=? AND video_id=?", (item_id, aid, vid))
        # Removed from the playlist on YouTube (but not waiting on a local change)
        for r in db.rows("SELECT video_id, item_id FROM album_videos WHERE album_id=? AND item_id IS NOT NULL AND removed=0", (aid,)):
            if r["item_id"] not in item_ids:
                db.run("DELETE FROM album_videos WHERE album_id=? AND video_id=?", (aid, r["video_id"]))
    # Playlist deleted on YouTube: delete the album too (its videos stay)
    for a in db.rows("SELECT id, playlist_id FROM albums WHERE deleted=0 AND playlist_id IS NOT NULL"):
        if a["playlist_id"] not in live:
            db.run("DELETE FROM album_videos WHERE album_id=?", (a["id"],))
            db.run("DELETE FROM albums WHERE id=?", (a["id"],))
    state["yt_import"] = {**info, "at": db.now()}
    return info


# ---------- main YouTube loop ----------

def _youtube_loop():
    last_check = 0.0
    last_import = 0.0
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
                try:
                    ids = [r["youtube_id"] for r in db.rows(
                        """SELECT youtube_id FROM videos
                           WHERE youtube_id IS NOT NULL AND status='uploaded'
                           UNION
                           SELECT youtube_id FROM videos
                           WHERE youtube_id IS NOT NULL AND status='on_youtube' AND delete_after <= ?""",
                        (db.now(),))]
                    states = youtube.video_states(ids) if ids else {}
                    _check_youtube(states)
                    _safe_delete_due(states)
                except youtube.QuotaExceeded:
                    _pause_for_quota()
            if state["yt_import_now"] or time.time() - last_import > 1800:
                state["yt_import_now"] = False
                last_import = time.time()
                try:
                    _sync_albums()  # push local album changes first so they aren't undone
                    import_from_youtube()
                except youtube.QuotaExceeded:
                    pass
                except Exception:
                    log.exception("YouTube import failed")
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
    # An upload interrupted by closing the app resumes from its saved session.
    db.run("UPDATE videos SET status='queued' WHERE status='uploading'")
    notify.start()
    threading.Thread(target=_backfill_locations, daemon=True, name="geo_backfill").start()
    faces.start()
    for fn in (_media_loop, _youtube_loop, _watch_loop):
        threading.Thread(target=fn, daemon=True, name=fn.__name__).start()
