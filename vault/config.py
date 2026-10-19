import json
import os
import secrets
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
    "watch_folders": [],  # [{"path": ..., "source": "phone" | "pc"}], checked every 15 s
    "autostart_initialized": False,
    "event_gap_hours": 3,  # clips closer together than this form one event
}


def load_settings() -> dict:
    s = dict(DEFAULTS)
    if SETTINGS_PATH.exists():
        s.update(json.loads(SETTINGS_PATH.read_text("utf-8")))
    if not s["pin"]:
        s["pin"] = f"{secrets.randbelow(10**6):06d}"
        save_settings(s)
    return s


def save_settings(s: dict) -> None:
    SETTINGS_PATH.write_text(json.dumps(s, indent=2), "utf-8")
