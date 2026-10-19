import re
import time

from google.auth.exceptions import RefreshError
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from urllib.parse import parse_qs, urlparse

from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

from .config import CLIENT_SECRET, TOKEN_PATH

# Full YouTube scope: upload, read status, and manage playlists (albums).
SCOPES = ["https://www.googleapis.com/auth/youtube"]
CHUNK = 16 * 1024 * 1024
RETRYABLE_STATUS = {500, 502, 503, 504}


class QuotaExceeded(Exception):
    """Daily API quota or channel upload limit reached."""


def has_client_secret() -> bool:
    return CLIENT_SECRET.exists()


_auth = {"state": "none"}  # none | ok | expired | scope


def auth_state() -> str:
    """'none' never connected, 'ok', 'expired' login ran out (e.g. the 7-day
    Testing-mode limit), 'scope' token predates albums and must be reconnected."""
    credentials()
    return _auth["state"]


def credentials() -> Credentials | None:
    if not TOKEN_PATH.exists():
        _auth["state"] = "none"
        return None
    # Load without forcing scopes so we can see what was actually granted.
    creds = Credentials.from_authorized_user_file(str(TOKEN_PATH))
    if not set(SCOPES) <= set(creds.scopes or []):
        _auth["state"] = "scope"
        return None
    if not creds.valid:
        try:
            creds.refresh(Request())
        except RefreshError:
            _auth["state"] = "expired"
            return None
        except Exception:  # no internet right now; not a login problem
            _auth["state"] = "offline"
            return None
        TOKEN_PATH.write_text(creds.to_json(), "utf-8")
    _auth["state"] = "ok"
    return creds


# Sign-in that works from any device. Google redirects to this PC's own
# localhost callback: on the PC that finishes by itself; on the phone the page
# can't load, so the user pastes its address into the app instead.
_pending_logins: dict[str, tuple[str, float]] = {}  # state -> (PKCE verifier, created)


def _flow(redirect_uri: str, state: str | None = None) -> Flow:
    return Flow.from_client_secrets_file(str(CLIENT_SECRET), SCOPES, redirect_uri=redirect_uri, state=state)


def login_url(redirect_uri: str) -> str:
    flow = _flow(redirect_uri)
    url, state = flow.authorization_url(access_type="offline", prompt="consent")
    now = time.time()
    for s, (_, t) in list(_pending_logins.items()):
        if now - t > 1800:
            del _pending_logins[s]
    _pending_logins[state] = (flow.code_verifier, now)
    return url


def finish_login(redirect_uri: str, pasted: str) -> None:
    """`pasted` is the full callback address (or just its query string)."""
    query = parse_qs(urlparse(pasted.strip()).query or pasted.strip().lstrip("?"))
    if "error" in query:
        raise ValueError("Google sign-in was cancelled: " + query["error"][0])
    code, state = query.get("code", [None])[0], query.get("state", [None])[0]
    if not code or not state:
        raise ValueError("That address doesn't contain a sign-in code. Copy the whole address.")
    if state not in _pending_logins:
        raise ValueError("This sign-in link has expired. Tap Connect YouTube again.")
    verifier, _ = _pending_logins.pop(state)
    flow = _flow(redirect_uri, state)
    flow.code_verifier = verifier
    flow.fetch_token(code=code)
    TOKEN_PATH.write_text(flow.credentials.to_json(), "utf-8")


def logout() -> None:
    TOKEN_PATH.unlink(missing_ok=True)


def _service():
    creds = credentials()
    if not creds:
        raise RuntimeError("Not connected to YouTube")
    return build("youtube", "v3", credentials=creds, cache_discovery=False)


def _is_quota(err: HttpError) -> bool:
    text = str(err.content)
    return err.resp.status in (403, 429) and (
        "quotaExceeded" in text or "uploadLimitExceeded" in text or "rateLimitExceeded" in text)


def channel_title() -> str | None:
    try:
        res = _service().channels().list(part="snippet", mine=True).execute()
        items = res.get("items") or []
        return items[0]["snippet"]["title"] if items else None
    except Exception:
        return None


def upload(path: str, title: str, description: str, recorded_iso: str, on_progress) -> str:
    """Resumable upload as a private video. Returns the YouTube video id."""
    body = {
        "snippet": {"title": title, "description": description, "categoryId": "22"},
        "status": {"privacyStatus": "private", "selfDeclaredMadeForKids": False},
        "recordingDetails": {"recordingDate": recorded_iso},
    }
    media = MediaFileUpload(path, mimetype="application/octet-stream",
                            chunksize=CHUNK, resumable=True)
    request = _service().videos().insert(
        part="snippet,status,recordingDetails", body=body, media_body=media)

    response, failures = None, 0
    while response is None:
        try:
            status, response = request.next_chunk()
            failures = 0
            if status:
                on_progress(status.progress())
        except HttpError as e:
            if _is_quota(e):
                raise QuotaExceeded(str(e)) from e
            if e.resp.status not in RETRYABLE_STATUS:
                raise
            failures += 1
        except (OSError, TimeoutError):  # dropped connection, Wi-Fi blip
            failures += 1
        if failures:
            if failures > 8:
                raise RuntimeError("Upload kept failing (network?)")
            time.sleep(min(2 ** failures, 120))
    return response["id"]


def _call(request):
    try:
        return request.execute()
    except HttpError as e:
        if _is_quota(e):
            raise QuotaExceeded(str(e)) from e
        raise


def is_not_found(err: Exception) -> bool:
    return isinstance(err, HttpError) and err.resp.status == 404


def create_playlist(title: str) -> str:
    body = {"snippet": {"title": title[:150], "description": "Footage Vault album"},
            "status": {"privacyStatus": "private"}}
    return _call(_service().playlists().insert(part="snippet,status", body=body))["id"]


def rename_playlist(playlist_id: str, title: str) -> None:
    body = {"id": playlist_id, "snippet": {"title": title[:150], "description": "Footage Vault album"}}
    _call(_service().playlists().update(part="snippet", body=body))


def delete_playlist(playlist_id: str) -> None:
    _call(_service().playlists().delete(id=playlist_id))


def add_to_playlist(playlist_id: str, video_id: str) -> str:
    body = {"snippet": {"playlistId": playlist_id,
                        "resourceId": {"kind": "youtube#video", "videoId": video_id}}}
    return _call(_service().playlistItems().insert(part="snippet", body=body))["id"]


def remove_from_playlist(item_id: str) -> None:
    _call(_service().playlistItems().delete(id=item_id))


def _iso_duration(value: str) -> float:
    m = re.fullmatch(r"P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", value or "")
    if not m:
        return 0.0
    d, h, mi, s = (int(x or 0) for x in m.groups())
    return float(((d * 24 + h) * 60 + mi) * 60 + s)


def video_states(ids: list[str]) -> dict[str, dict]:
    """{id: {upload_status, failure, duration}}; ids missing from the result no longer exist."""
    out = {}
    svc = _service()
    for i in range(0, len(ids), 50):
        res = svc.videos().list(part="status,contentDetails",
                                id=",".join(ids[i:i + 50])).execute()
        for item in res.get("items", []):
            st = item.get("status", {})
            out[item["id"]] = {
                "upload_status": st.get("uploadStatus"),
                "failure": st.get("failureReason") or st.get("rejectionReason"),
                "duration": _iso_duration(item.get("contentDetails", {}).get("duration", "")),
            }
    return out
