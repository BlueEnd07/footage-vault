import json
import os
import shutil
import socket
import subprocess
import threading
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import db, events, media, notify, worker, youtube
from .config import INCOMING, PREVIEWS, STATIC, THUMBS, load_settings, save_settings

LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}
# Reachable without the PIN: app shell files the browser fetches without cookies.
PUBLIC_PATHS = ("/pin", "/manifest.webmanifest", "/sw.js", "/static/")


@asynccontextmanager
async def lifespan(_app):
    worker.start()
    yield


app = FastAPI(lifespan=lifespan)


def is_local(request: Request) -> bool:
    """True only for a browser on this PC. Requests proxied by `tailscale serve`
    also arrive from 127.0.0.1, so those are recognised by their headers."""
    if not request.client or request.client.host not in LOCAL_HOSTS:
        return False
    h = request.headers
    if "x-forwarded-for" in h or "tailscale-user-login" in h or "x-forwarded-host" in h:
        return False
    return h.get("host", "").startswith(("localhost", "127.0.0.1", "[::1]"))


def require_local(request: Request):
    if not is_local(request):
        raise HTTPException(403, "Only available on the PC running the vault")


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the LAN interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


_ts_cache = {"at": 0.0, "info": None}


def tailscale_info() -> dict | None:
    """{'url': 'https://pc.tailnet.ts.net', 'https': bool} if Tailscale is running."""
    if time.time() - _ts_cache["at"] < 60:
        return _ts_cache["info"]
    _ts_cache["at"] = time.time()
    exe = shutil.which("tailscale") or r"C:\Program Files\Tailscale\tailscale.exe"
    info = None
    try:
        r = subprocess.run([exe, "status", "--json"], capture_output=True, text=True, timeout=5,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        s = json.loads(r.stdout)
        name = (s.get("Self") or {}).get("DNSName", "").rstrip(".")
        if s.get("BackendState") == "Running" and name:
            info = {"url": f"https://{name}", "https": bool(s.get("CertDomains"))}
    except Exception:
        pass
    _ts_cache["info"] = info
    return info


# ---------- PIN gate for phones / other devices ----------

PIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Footage Vault</title>
<link rel="manifest" href="/manifest.webmanifest"><meta name="theme-color" content="#111214">
<style>body{font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0}
form{display:grid;gap:12px;text-align:center}input,button{font-size:20px;padding:12px;border-radius:10px;border:0}
input{text-align:center;letter-spacing:6px}button{background:#4f8cff;color:#fff}</style></head>
<body><form method="post" action="/pin"><div>Enter the PIN shown on your PC</div>
<input name="pin" inputmode="numeric" autofocus><button>Open vault</button></form></body></html>"""


@app.middleware("http")
async def pin_gate(request: Request, call_next):
    path = request.url.path
    if is_local(request) or path.startswith(PUBLIC_PATHS):
        return await call_next(request)
    if request.cookies.get("vault_pin") == load_settings()["pin"]:
        return await call_next(request)
    if path.startswith("/api"):
        return JSONResponse({"detail": "PIN required"}, status_code=401)
    return HTMLResponse(PIN_PAGE)


@app.post("/pin")
async def submit_pin(request: Request):
    form = (await request.body()).decode()
    pin = dict(p.split("=", 1) for p in form.split("&") if "=" in p).get("pin", "")
    if pin != load_settings()["pin"]:
        return HTMLResponse(PIN_PAGE.replace("Enter the PIN", "Wrong PIN. Enter the PIN"))
    resp = HTMLResponse('<meta http-equiv="refresh" content="0;url=/">')
    resp.set_cookie("vault_pin", pin, max_age=365 * 86400, httponly=True, samesite="lax",
                    secure=request.headers.get("x-forwarded-proto") == "https")
    return resp


# ---------- videos ----------

@app.get("/api/videos")
def list_videos():
    return db.rows("""SELECT v.id, v.name, v.source, v.size, v.duration, v.width, v.height, v.recorded_at,
                             v.status, v.progress, v.error, v.youtube_id, v.delete_after, v.deleted_at,
                             v.phone_cleared_at, v.sync_path IS NOT NULL AS synced, v.lat, v.lon, v.place,
                             (SELECT group_concat(av.album_id) FROM album_videos av
                               WHERE av.video_id = v.id AND av.removed = 0) AS albums
                      FROM videos v ORDER BY v.recorded_at DESC""")


@app.get("/api/status")
def status(request: Request):
    counts = {r["status"]: r["n"] for r in db.rows("SELECT status, COUNT(*) n FROM videos GROUP BY status")}
    s = load_settings()
    out = {
        "local": is_local(request),
        "has_client_secret": youtube.has_client_secret(),
        "connected": youtube.credentials() is not None,
        "auth": youtube.auth_state(),
        "user_paused": worker.state["user_paused"],
        "paused_until": worker.state["paused_until"].strftime("%d %b %H:%M") if worker.state["paused_until"] else None,
        "pause_reason": worker.state["pause_reason"],
        "import": worker.state["import"],
        "counts": counts,
        "delete_after_days": s["delete_after_days"],
        "push_devices": db.row("SELECT COUNT(*) n FROM push_subs")["n"],
    }
    if is_local(request):
        out["lan_url"] = f"http://{lan_ip()}:{s['port']}"
        out["pin"] = s["pin"]
        out["tailscale"] = tailscale_info()
        out["watch_folders"] = s.get("watch_folders") or []
    return out


@app.get("/api/channel")
def channel():
    return {"title": youtube.channel_title()}


@app.put("/api/upload")
async def upload_from_device(request: Request, name: str, modified: int = 0):
    """Raw file body from the browser (used by the phone). Saved to data/incoming."""
    safe = "".join(c for c in Path(name).name if c not in '<>:"/\\|?*') or "video.mp4"
    if Path(safe).suffix.lower() not in media.VIDEO_EXTS:
        raise HTTPException(400, f"{safe} is not a supported video type")
    dest = INCOMING / f"{uuid.uuid4().hex[:8]}_{safe}"
    try:
        with open(dest, "wb") as f:
            async for chunk in request.stream():
                f.write(chunk)
    except BaseException:
        dest.unlink(missing_ok=True)  # phone lost Wi-Fi / page closed mid-send
        raise
    fallback = datetime.fromtimestamp(modified / 1000) if modified else None
    try:
        vid = await run_in_threadpool(worker.register, dest, "phone", fallback, safe)
    except Exception as e:
        dest.unlink(missing_ok=True)
        raise HTTPException(400, f"Could not read video: {e}")
    if vid is None:
        dest.unlink(missing_ok=True)
        return {"duplicate": True}
    return {"id": vid}


class FolderReq(BaseModel):
    folder: str
    source: str = "pc"


def _folder(raw: str) -> Path:
    raw = raw.strip().strip('"')
    folder = Path(raw)
    if not raw or not folder.is_absolute() or not folder.is_dir():
        raise HTTPException(400, "Folder not found")
    return folder


@app.post("/api/import")
def import_folder(req: FolderReq, request: Request):
    require_local(request)
    folder = _folder(req.folder)
    if worker.state["import"] and worker.state["import"]["running"]:
        raise HTTPException(409, "An import is already running")
    threading.Thread(target=worker.import_folder, args=(str(folder),), daemon=True).start()
    return {"ok": True}


@app.post("/api/watch")
def add_watch_folder(req: FolderReq, request: Request):
    require_local(request)
    folder = str(_folder(req.folder))
    s = load_settings()
    folders = [f for f in s.get("watch_folders") or [] if f["path"].lower() != folder.lower()]
    folders.append({"path": folder, "source": "phone" if req.source == "phone" else "pc"})
    s["watch_folders"] = folders
    save_settings(s)
    return {"ok": True}


@app.post("/api/watch/remove")
def remove_watch_folder(req: FolderReq, request: Request):
    require_local(request)
    s = load_settings()
    s["watch_folders"] = [f for f in s.get("watch_folders") or [] if f["path"] != req.folder]
    save_settings(s)
    return {"ok": True}


@app.post("/api/videos/{vid}/retry")
def retry(vid: int):
    v = db.row("SELECT status, youtube_id FROM videos WHERE id=?", (vid,))
    if not v or v["status"] not in ("failed", "hold"):
        raise HTTPException(400, "Nothing to retry")
    if v["status"] == "hold":
        # Re-run the safety checks now.
        db.run("UPDATE videos SET status='on_youtube', error=NULL, delete_after=? WHERE id=?", (db.now(), vid))
    elif not (PREVIEWS / f"{vid}.mp4").exists():
        db.run("UPDATE videos SET status='new', error=NULL, attempts=0 WHERE id=?", (vid,))
    else:
        db.run("UPDATE videos SET status='queued', error=NULL, attempts=0, youtube_id=NULL WHERE id=?", (vid,))
    return {"ok": True}


# ---------- free up space on the phone ----------

class VideoIdsReq(BaseModel):
    video_ids: list[int]


@app.get("/api/free-space")
def free_space_preview():
    c = worker.free_space_candidates()
    pick = lambda rows: [{"id": r["id"], "name": r["name"], "size": r["size"], "recorded_at": r["recorded_at"]} for r in rows]
    return {"synced": pick(c["synced"]), "manual": pick(c["manual"]),
            "synced_bytes": sum(r["size"] for r in c["synced"]),
            "has_phone_sync": any(f.get("source") == "phone" for f in load_settings().get("watch_folders") or [])}


@app.post("/api/free-space")
def free_space_run():
    try:
        return worker.free_space()
    except youtube.QuotaExceeded:
        raise HTTPException(503, "YouTube's daily limit is reached, so videos can't be re-checked. Try again tomorrow.")
    except Exception as e:
        if youtube.credentials() is None:
            raise HTTPException(503, "YouTube isn't connected, so videos can't be re-checked. Reconnect first.")
        raise HTTPException(500, f"Couldn't free up space: {e}")


class DeleteReq(BaseModel):
    video_ids: list[int]
    delete_copies: bool = True


@app.post("/api/videos/delete")
def delete_forever(req: DeleteReq):
    """Permanent: deletes from YouTube and the vault (see worker.delete_forever)."""
    if not req.video_ids:
        return {"deleted": 0, "skipped": []}
    if youtube.credentials() is None and db.row(
            f"SELECT 1 FROM videos WHERE youtube_id IS NOT NULL AND id IN ({','.join('?' * len(req.video_ids))})",
            req.video_ids):
        raise HTTPException(503, "YouTube isn't connected, so the YouTube copy can't be deleted. Reconnect first.")
    return worker.delete_forever(req.video_ids, req.delete_copies)


@app.post("/api/free-space/mark")
def free_space_mark(req: VideoIdsReq):
    """For videos added by hand: the user deleted them on the phone themselves."""
    worker.mark_phone_cleared(req.video_ids)
    return {"ok": True}


# ---------- albums ----------

class AlbumReq(BaseModel):
    name: str


@app.get("/api/events")
def list_events():
    vids = db.rows("SELECT id, recorded_at, lat, lon, place FROM videos")
    return events.build(vids, float(load_settings().get("event_gap_hours", 3)))


@app.get("/api/albums")
def list_albums():
    return db.rows("""
        SELECT a.id, a.name, a.playlist_id,
               (SELECT COUNT(*) FROM album_videos av WHERE av.album_id = a.id AND av.removed = 0) AS count,
               (SELECT av.video_id FROM album_videos av JOIN videos v ON v.id = av.video_id
                 WHERE av.album_id = a.id AND av.removed = 0
                 ORDER BY v.recorded_at DESC LIMIT 1) AS cover
        FROM albums a WHERE a.deleted = 0 ORDER BY a.name COLLATE NOCASE""")


def _album_name(name: str) -> str:
    name = name.strip()[:150]
    if not name:
        raise HTTPException(400, "Album name is empty")
    return name


@app.post("/api/albums")
def create_album(req: AlbumReq):
    return {"id": db.run("INSERT INTO albums (name, created_at) VALUES (?, ?)", (_album_name(req.name), db.now()))}


@app.post("/api/albums/{aid}/rename")
def rename_album(aid: int, req: AlbumReq):
    db.run("UPDATE albums SET name=?, title_synced=0 WHERE id=? AND deleted=0", (_album_name(req.name), aid))
    return {"ok": True}


@app.post("/api/albums/{aid}/delete")
def delete_album(aid: int):
    """Removes the album and its YouTube playlist. The videos themselves stay."""
    db.run("UPDATE albums SET deleted=1 WHERE id=?", (aid,))
    return {"ok": True}


@app.post("/api/albums/{aid}/add")
def add_to_album(aid: int, req: VideoIdsReq):
    if not db.row("SELECT id FROM albums WHERE id=? AND deleted=0", (aid,)):
        raise HTTPException(404, "Album not found")
    for vid in req.video_ids:
        db.run("""INSERT INTO album_videos (album_id, video_id, added_at) VALUES (?,?,?)
                  ON CONFLICT(album_id, video_id) DO UPDATE SET removed=0""", (aid, vid, db.now()))
    return {"ok": True}


@app.post("/api/albums/{aid}/remove")
def remove_from_album(aid: int, req: VideoIdsReq):
    for vid in req.video_ids:
        db.run("UPDATE album_videos SET removed=1 WHERE album_id=? AND video_id=?", (aid, vid))
    return {"ok": True}


# ---------- YouTube account ----------

def _redirect_uri() -> str:
    # Desktop OAuth clients accept any localhost address, so no Google Cloud change is needed.
    return f"http://localhost:{load_settings()['port']}/oauth/callback"


@app.post("/api/youtube/login")
def youtube_login():
    """Works from the PC and the phone; returns the Google sign-in address to open."""
    if not youtube.has_client_secret():
        raise HTTPException(400, "client_secret.json is missing (see SETUP.md)")
    return {"url": youtube.login_url(_redirect_uri())}


class FinishReq(BaseModel):
    url: str


@app.post("/api/youtube/finish")
def youtube_finish(req: FinishReq):
    """Phone: the user pastes the address of the page Google sent them to."""
    try:
        youtube.finish_login(_redirect_uri(), req.url)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except Exception as e:
        raise HTTPException(400, f"Google didn't accept the code ({e}). Tap Connect YouTube and try again.")
    return {"ok": True}


DONE_PAGE = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Footage Vault</title><body style="font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0;text-align:center">
<div><h2>{title}</h2><p>{body}</p></div>"""


@app.get("/oauth/callback")
def oauth_callback(request: Request):
    """Google lands here when sign-in was done on this PC."""
    try:
        youtube.finish_login(_redirect_uri(), str(request.url))
    except Exception as e:
        return HTMLResponse(DONE_PAGE.format(title="Couldn't connect YouTube", body=str(e)), status_code=400)
    return HTMLResponse(DONE_PAGE.format(title="✓ YouTube connected",
                                         body="You can close this tab and go back to Footage Vault."))


@app.post("/api/youtube/logout")
def youtube_logout(request: Request):
    require_local(request)
    youtube.logout()
    return {"ok": True}


class SettingsReq(BaseModel):
    delete_after_days: int


@app.post("/api/settings")
def update_settings(req: SettingsReq, request: Request):
    require_local(request)
    s = load_settings()
    s["delete_after_days"] = max(0, min(req.delete_after_days, 365))
    save_settings(s)
    return {"ok": True}


# ---------- notifications ----------

@app.get("/api/push/key")
def push_key():
    return {"key": notify.public_key()}


@app.post("/api/push/subscribe")
async def push_subscribe(request: Request):
    sub = await request.json()
    if not isinstance(sub, dict) or not str(sub.get("endpoint", "")).startswith("https://"):
        raise HTTPException(400, "Bad subscription")
    notify.subscribe(sub)
    return {"ok": True}


@app.post("/api/push/unsubscribe")
async def push_unsubscribe(request: Request):
    notify.unsubscribe((await request.json()).get("endpoint", ""))
    return {"ok": True}


@app.post("/api/push/test")
def push_test():
    n = notify.send("Footage Vault", "Notifications are working 🎉", "/", "test")
    return {"sent": n}


# ---------- app shell ----------

MANIFEST = {
    "name": "Footage Vault",
    "short_name": "Vault",
    "start_url": "/",
    "scope": "/",
    "display": "standalone",
    "background_color": "#111214",
    "theme_color": "#111214",
    "icons": [
        {"src": "/static/icon-192.png", "sizes": "192x192", "type": "image/png"},
        {"src": "/static/icon-512.png", "sizes": "512x512", "type": "image/png"},
        {"src": "/static/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
    ],
}


@app.get("/manifest.webmanifest")
def manifest():
    return Response(json.dumps(MANIFEST), media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    # Served from the root so it controls the whole app.
    return FileResponse(STATIC / "sw.js", media_type="text/javascript",
                        headers={"Cache-Control": "no-cache", "Service-Worker-Allowed": "/"})


app.mount("/media/thumbs", StaticFiles(directory=THUMBS), name="thumbs")
app.mount("/media/previews", StaticFiles(directory=PREVIEWS), name="previews")
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})
