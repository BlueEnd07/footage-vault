import socket
import threading
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import db, media, worker, youtube
from .config import INCOMING, PREVIEWS, STATIC, THUMBS, load_settings, save_settings

LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


@asynccontextmanager
async def lifespan(_app):
    worker.start()
    yield


app = FastAPI(lifespan=lifespan)


def is_local(request: Request) -> bool:
    return bool(request.client) and request.client.host in LOCAL_HOSTS


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


# ---------- PIN gate for phones / other devices ----------

PIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Footage Vault</title>
<style>body{font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0}
form{display:grid;gap:12px;text-align:center}input,button{font-size:20px;padding:12px;border-radius:10px;border:0}
input{text-align:center;letter-spacing:6px}button{background:#4f8cff;color:#fff}</style></head>
<body><form method="post" action="/pin"><div>Enter the PIN shown on your PC</div>
<input name="pin" inputmode="numeric" autofocus><button>Open vault</button></form></body></html>"""


@app.middleware("http")
async def pin_gate(request: Request, call_next):
    if is_local(request) or request.url.path == "/pin":
        return await call_next(request)
    if request.cookies.get("vault_pin") == load_settings()["pin"]:
        return await call_next(request)
    if request.url.path.startswith("/api"):
        return JSONResponse({"detail": "PIN required"}, status_code=401)
    return HTMLResponse(PIN_PAGE)


@app.post("/pin")
async def submit_pin(request: Request):
    form = (await request.body()).decode()
    pin = dict(p.split("=", 1) for p in form.split("&") if "=" in p).get("pin", "")
    if pin != load_settings()["pin"]:
        return HTMLResponse(PIN_PAGE.replace("Enter the PIN", "Wrong PIN. Enter the PIN"))
    resp = HTMLResponse('<meta http-equiv="refresh" content="0;url=/">')
    resp.set_cookie("vault_pin", pin, max_age=365 * 86400, httponly=True, samesite="lax")
    return resp


# ---------- API ----------

@app.get("/api/videos")
def list_videos():
    return db.rows("""SELECT id, name, source, size, duration, width, height, recorded_at, status,
                             progress, error, youtube_id, delete_after, deleted_at
                      FROM videos ORDER BY recorded_at DESC""")


@app.get("/api/status")
def status(request: Request):
    counts = {r["status"]: r["n"] for r in db.rows("SELECT status, COUNT(*) n FROM videos GROUP BY status")}
    connected = youtube.credentials() is not None
    s = load_settings()
    out = {
        "local": is_local(request),
        "has_client_secret": youtube.has_client_secret(),
        "connected": connected,
        "login_running": worker.state["login_running"],
        "paused_until": worker.state["paused_until"].strftime("%d %b %H:%M") if worker.state["paused_until"] else None,
        "pause_reason": worker.state["pause_reason"],
        "import": worker.state["import"],
        "counts": counts,
        "delete_after_days": s["delete_after_days"],
    }
    if is_local(request):
        out["lan_url"] = f"http://{lan_ip()}:{s['port']}"
        out["pin"] = s["pin"]
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
    with open(dest, "wb") as f:
        async for chunk in request.stream():
            f.write(chunk)
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


class ImportReq(BaseModel):
    folder: str


@app.post("/api/import")
def import_folder(req: ImportReq, request: Request):
    require_local(request)
    raw = req.folder.strip().strip('"')
    folder = Path(raw)
    if not raw or not folder.is_absolute() or not folder.is_dir():
        raise HTTPException(400, "Folder not found")
    if worker.state["import"] and worker.state["import"]["running"]:
        raise HTTPException(409, "An import is already running")
    threading.Thread(target=worker.import_folder, args=(str(folder),), daemon=True).start()
    return {"ok": True}


@app.post("/api/youtube/login")
def youtube_login(request: Request):
    require_local(request)
    if not youtube.has_client_secret():
        raise HTTPException(400, "client_secret.json is missing (see SETUP.md)")
    if worker.state["login_running"]:
        return {"ok": True}

    def run():
        worker.state["login_running"] = True
        try:
            youtube.login()
        finally:
            worker.state["login_running"] = False

    threading.Thread(target=run, daemon=True).start()
    return {"ok": True}


@app.post("/api/youtube/logout")
def youtube_logout(request: Request):
    require_local(request)
    youtube.logout()
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


class SettingsReq(BaseModel):
    delete_after_days: int


@app.post("/api/settings")
def update_settings(req: SettingsReq, request: Request):
    require_local(request)
    s = load_settings()
    s["delete_after_days"] = max(0, min(req.delete_after_days, 365))
    save_settings(s)
    return {"ok": True}


# ---------- files ----------

app.mount("/media/thumbs", StaticFiles(directory=THUMBS), name="thumbs")
app.mount("/media/previews", StaticFiles(directory=PREVIEWS), name="previews")
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})
