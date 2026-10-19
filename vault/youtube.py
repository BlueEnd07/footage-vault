import re
import time

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

from .config import CLIENT_SECRET, TOKEN_PATH

SCOPES = [
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtube.readonly",
]
CHUNK = 16 * 1024 * 1024
RETRYABLE_STATUS = {500, 502, 503, 504}


class QuotaExceeded(Exception):
    """Daily API quota or channel upload limit reached."""


def has_client_secret() -> bool:
    return CLIENT_SECRET.exists()


def credentials() -> Credentials | None:
    if not TOKEN_PATH.exists():
        return None
    creds = Credentials.from_authorized_user_file(str(TOKEN_PATH), SCOPES)
    if creds.valid:
        return creds
    if creds.expired and creds.refresh_token:
        try:
            creds.refresh(Request())
        except Exception:
            return None
        TOKEN_PATH.write_text(creds.to_json(), "utf-8")
        return creds
    return None


def login() -> None:
    """Opens Google sign-in in this PC's browser and stores the token."""
    flow = InstalledAppFlow.from_client_secrets_file(str(CLIENT_SECRET), SCOPES)
    creds = flow.run_local_server(port=0, open_browser=True, prompt="consent")
    TOKEN_PATH.write_text(creds.to_json(), "utf-8")


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
