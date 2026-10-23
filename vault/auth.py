"""Local-network authentication helpers: host checks, sessions and login throttling.

The PC UI is trusted without a PIN only when the request really came from this
machine. The Host header must name a loopback host exactly, so a public name that
merely starts with "localhost" (DNS rebinding) is not accepted.
"""
import hashlib
import ipaddress
import secrets
import threading
import time
from datetime import datetime, timedelta
from urllib.parse import urlsplit

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
SAFE_METHODS = {"GET", "HEAD", "OPTIONS", "TRACE"}
SESSION_COOKIE = "vault_session"
SESSION_DAYS = 30


def host_name(value: str | None) -> str | None:
    """The host part of a Host/authority header, without a port."""
    value = (value or "").strip().lower()
    if not value:
        return None
    if value.startswith("["):  # [::1] or [::1]:8765
        end = value.find("]")
        return value[1:end] if end != -1 else None
    if value.count(":") == 1:  # host:port
        value = value.rsplit(":", 1)[0]
    return value or None


def is_local_host(value: str | None) -> bool:
    name = host_name(value)
    if not name:
        return False
    if name in LOCAL_HOSTS:
        return True
    try:
        return ipaddress.ip_address(name).is_loopback
    except ValueError:
        return False


def same_origin(origin: str | None, host: str | None) -> bool:
    if not origin:
        return False
    parsed = urlsplit(origin)
    if parsed.scheme not in ("http", "https"):
        return False
    if (host_name(parsed.netloc) or "") != (host_name(host) or ""):
        return False
    # A Host header has no scheme, so only compare ports when it names one.
    host_port = _explicit_port(host)
    origin_port = parsed.port or (443 if parsed.scheme == "https" else 80)
    return host_port is None or host_port == origin_port


def _explicit_port(value: str | None) -> int | None:
    value = (value or "").strip()
    if value.startswith("["):
        rest = value.split("]", 1)[1]
        tail = rest[1:] if rest.startswith(":") else ""
    elif value.count(":") == 1:
        tail = value.rsplit(":", 1)[1]
    else:
        tail = ""
    return int(tail) if tail.isdigit() else None


def cross_site(request) -> bool:
    """True for a state-changing request clearly started by another site."""
    if request.method in SAFE_METHODS:
        return False
    site = request.headers.get("sec-fetch-site")
    if site and site not in ("same-origin", "none"):
        return True
    origin = request.headers.get("origin")
    if origin is None:
        return False
    return not same_origin(origin, request.headers.get("host"))


def new_session() -> tuple[str, str]:
    """(cookie value, value stored in settings). Only the hash is kept on disk."""
    token = secrets.token_urlsafe(32)
    return token, token_hash(token)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def session_expiry(now: datetime | None = None) -> str:
    return ((now or datetime.now()) + timedelta(days=SESSION_DAYS)).strftime("%Y-%m-%d %H:%M:%S")


def valid_session(token: str | None, sessions: dict | None, now: datetime | None = None) -> bool:
    if not token or not isinstance(sessions, dict):
        return False
    expiry = sessions.get(token_hash(token))
    if not expiry:
        return False
    try:
        return datetime.strptime(expiry, "%Y-%m-%d %H:%M:%S") > (now or datetime.now())
    except (TypeError, ValueError):
        return False


def prune_sessions(sessions: dict, now: datetime | None = None, limit: int = 50) -> dict:
    current = now or datetime.now()
    live = {}
    for key, expiry in (sessions or {}).items():
        try:
            if datetime.strptime(expiry, "%Y-%m-%d %H:%M:%S") > current:
                live[key] = expiry
        except (TypeError, ValueError):
            continue
    if len(live) > limit:  # keep the most recently expiring ones
        live = dict(sorted(live.items(), key=lambda item: item[1], reverse=True)[:limit])
    return live


class LoginLimiter:
    """Exponential delay after repeated wrong PINs, per client address."""

    def __init__(self, limit: int = 5, base_delay: int = 30, max_delay: int = 900,
                 forget_after: int = 15 * 60):
        self.limit = limit
        self.base_delay = base_delay
        self.max_delay = max_delay
        self.forget_after = forget_after
        self._lock = threading.Lock()
        self._state: dict[str, tuple[int, float]] = {}  # address -> (failures, last_failure)

    def retry_after(self, address: str) -> int:
        with self._lock:
            failures, last = self._state.get(address, (0, 0.0))
            if failures < self.limit:
                return 0
            delay = min(self.base_delay * (2 ** (failures - self.limit)), self.max_delay)
            return max(0, int(delay - (time.time() - last)))

    def fail(self, address: str) -> None:
        with self._lock:
            failures, last = self._state.get(address, (0, 0.0))
            if time.time() - last > self.forget_after:
                failures = 0
            self._state[address] = (failures + 1, time.time())

    def reset(self, address: str) -> None:
        with self._lock:
            self._state.pop(address, None)


limiter = LoginLimiter()
