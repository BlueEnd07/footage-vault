import json
import logging
import os
import secrets
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(os.environ.get("VAULT_DATA") or ROOT / "data")
THUMBS = DATA / "thumbs"
PREVIEWS = DATA / "previews"
INCOMING = DATA / "incoming"  # videos sent from the phone land here
FACES = DATA / "faces"  # face crops shown in People (made on this PC)
DB_PATH = DATA / "vault.db"
TOKEN_PATH = DATA / "token.json"
SETTINGS_PATH = DATA / "settings.json"
CLIENT_SECRET = ROOT / "client_secret.json"
STATIC = ROOT / "web"
MODELS = ROOT / "models"  # face detection/recognition models, downloaded on first run

for d in (DATA, THUMBS, PREVIEWS, INCOMING, FACES):
    d.mkdir(parents=True, exist_ok=True)

DEFAULTS = {
    "port": 8765,
    "delete_after_days": 3,  # grace period after YouTube finishes processing
    "pin": None,  # required for devices other than this PC (e.g. the phone)
    "sessions": {},  # sha256(session cookie) -> expiry, one per signed-in device
    "watch_folders": [],  # [{"path": ..., "source": "phone" | "pc"}], checked every 15 s
    "autostart_initialized": False,
    "event_gap_hours": 3,  # clips closer together than this form one event
}

log = logging.getLogger("vault")
LOCK = threading.RLock()
_cache: dict = {"key": None, "value": None}


def _read_json() -> dict:
    try:
        # utf-8-sig also accepts the BOM that Windows editors often add.
        data = json.loads(SETTINGS_PATH.read_text("utf-8-sig"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        # Keep the unreadable file for the user, then start from defaults instead
        # of failing every request that needs the settings.
        backup = SETTINGS_PATH.with_suffix(".json.bad")
        try:
            os.replace(SETTINGS_PATH, backup)
            log.warning("settings.json was unreadable (%s); moved to %s", e, backup)
        except OSError:
            log.warning("settings.json was unreadable: %s", e)
        return {}


def load_settings() -> dict:
    with LOCK:
        try:
            st = SETTINGS_PATH.stat()
            key = (st.st_mtime_ns, st.st_size)
        except OSError:
            key = None
        if key is not None and key == _cache["key"] and _cache["value"] is not None:
            cached = dict(_cache["value"])
            if not isinstance(cached.get("sessions"), dict):
                cached["sessions"] = {}
            return cached

        s = dict(DEFAULTS)
        s["sessions"] = {}
        s.update(_read_json())
        if not isinstance(s.get("sessions"), dict):
            s["sessions"] = {}
        if not s.get("pin"):
            s["pin"] = f"{secrets.randbelow(10**8):08d}"
            save_settings(s)
        _remember(s)
        return dict(s)


def _remember(s: dict) -> None:
    try:
        st = SETTINGS_PATH.stat()
        _cache["key"] = (st.st_mtime_ns, st.st_size)
        _cache["value"] = dict(s)
    except OSError:
        _cache["key"] = None
        _cache["value"] = None


def save_settings(s: dict) -> None:
    """Write the whole file atomically so a crash can't leave half a JSON document."""
    with LOCK:
        SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(prefix=".settings-", suffix=".json", dir=SETTINGS_PATH.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(s, fh, indent=2)
                fh.flush()
                os.fsync(fh.fileno())
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
            os.replace(tmp, SETTINGS_PATH)
            _remember(s)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
