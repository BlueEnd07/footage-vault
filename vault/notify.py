"""Web Push notifications to the installed app on the phone (and any browser that opted in).

Events are collected and sent as one summary per minute, so a batch of 20
uploads gives one notification, not 20.
"""
import base64
import json
import logging
import threading
import time
from collections import Counter

from cryptography.hazmat.primitives import serialization
from py_vapid import Vapid
from pywebpush import WebPushException, webpush

from . import db
from .config import DATA

log = logging.getLogger("vault")
VAPID_PATH = DATA / "vapid_private.pem"
# Push services require a contact in the VAPID claim: a mailto: or a bare https origin
# (py_vapid rejects a URL with a path, which silently broke every notification).
VAPID_CLAIMS = {"sub": "https://github.com"}

_lock = threading.Lock()
_events: Counter = Counter()
_once: dict[str, str] = {}  # one-off alerts (quota, login) -> message


def _vapid() -> Vapid:
    if not VAPID_PATH.exists():
        v = Vapid()
        v.generate_keys()
        v.save_key(str(VAPID_PATH))
    return Vapid.from_file(str(VAPID_PATH))


def public_key() -> str:
    raw = _vapid().public_key.public_bytes(serialization.Encoding.X962,
                                           serialization.PublicFormat.UncompressedPoint)
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def subscribe(sub: dict) -> None:
    db.run("INSERT OR REPLACE INTO push_subs (endpoint, data, created_at) VALUES (?,?,?)",
           (sub["endpoint"], json.dumps(sub), db.now()))


def unsubscribe(endpoint: str) -> None:
    db.run("DELETE FROM push_subs WHERE endpoint=?", (endpoint,))


def send(title: str, body: str, url: str = "/", tag: str | None = None) -> int:
    """Sends to every subscribed device. Returns how many accepted it."""
    payload = json.dumps({"title": title, "body": body, "url": url, "tag": tag})
    sent = 0
    for s in db.rows("SELECT endpoint, data FROM push_subs"):
        try:
            webpush(subscription_info=json.loads(s["data"]), data=payload,
                    vapid_private_key=str(VAPID_PATH), vapid_claims=dict(VAPID_CLAIMS), ttl=24 * 3600)
            sent += 1
        except WebPushException as e:
            code = e.response.status_code if e.response is not None else None
            if code in (404, 410):  # device unsubscribed or app removed
                unsubscribe(s["endpoint"])
            else:
                log.warning("Push failed: %s", e)
        except Exception as e:
            log.warning("Push failed: %s", e)
    return sent


def event(kind: str, n: int = 1) -> None:
    """kind: received_phone | safe_phone | backed_up_pc | failed"""
    with _lock:
        _events[kind] += n


def alert_once(key: str, message: str) -> None:
    """For states like 'quota' or 'login'; repeated calls send only once until cleared."""
    with _lock:
        if key not in _once:
            _once[key] = message
            _events["alert:" + key] += 1


def clear_alert(key: str) -> None:
    with _lock:
        _once.pop(key, None)


def _plural(n: int, word: str) -> str:
    return f"{n} {word}{'' if n == 1 else 's'}"


def _flush() -> None:
    with _lock:
        ev = dict(_events)
        _events.clear()
        alerts = {k[6:]: _once.get(k[6:]) for k in ev if k.startswith("alert:")}
    if not ev:
        return
    if n := ev.get("safe_phone"):
        send("Safe to delete from your phone",
             f"{_plural(n, 'video')} safely on YouTube. Tap to free up space on your phone.",
             "/?filter=phone", "safe")
    if n := ev.get("backed_up_pc"):
        send("Backed up to YouTube", f"{_plural(n, 'video')} from the PC are safely on YouTube.", "/", "pc")
    if n := ev.get("received_phone"):
        send("Received from phone", f"{_plural(n, 'new video')} copied to the PC. Uploading to YouTube…",
             "/?filter=progress", "received")
    if n := ev.get("failed"):
        send("Upload problem", f"{_plural(n, 'video')} need attention.", "/?filter=problems", "failed")
    for key, msg in alerts.items():
        if msg:
            send("Footage Vault", msg, "/", key)


def _loop() -> None:
    while True:
        time.sleep(60)
        try:
            _flush()
        except Exception:
            log.exception("Notification flush failed")


def start() -> None:
    _vapid()
    threading.Thread(target=_loop, daemon=True, name="notify").start()
