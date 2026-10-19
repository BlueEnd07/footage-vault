import hashlib
import json
import os
import subprocess
from datetime import datetime
from pathlib import Path

VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".mts", ".m2ts", ".3gp",
              ".webm", ".wmv", ".mpg", ".mpeg"}
_NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

# Shortest side becomes N px, never upscaled.
_SHORT_SIDE = "scale='if(gt(iw,ih),-2,min({n},iw))':'if(gt(iw,ih),min({n},ih),-2)'"


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", creationflags=_NO_WINDOW)


def fingerprint(path: Path) -> str:
    """Fast duplicate check: size + first and last 4 MB (the whole file if it's small)."""
    size = path.stat().st_size
    h = hashlib.sha256(str(size).encode())
    with open(path, "rb") as f:
        h.update(f.read(4 << 20))
        if size > 8 << 20:
            f.seek(-(4 << 20), 2)
        h.update(f.read())
    return h.hexdigest()


def _parse_time(value: str) -> datetime | None:
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.year < 1990:  # cameras with an unset clock write 1904/1970
        return None
    if dt.tzinfo:
        dt = dt.astimezone()  # to local time
    return dt.replace(tzinfo=None, microsecond=0)


def probe(path: Path) -> dict:
    r = _run(["ffprobe", "-v", "error", "-print_format", "json",
              "-show_format", "-show_streams", str(path)])
    if r.returncode:
        raise RuntimeError((r.stderr.strip() or "ffprobe failed")[:300])
    info = json.loads(r.stdout)
    fmt = info.get("format", {})
    video = next((s for s in info.get("streams", [])
                  if s.get("codec_type") == "video"
                  and not s.get("disposition", {}).get("attached_pic")), None)
    if not video:
        raise RuntimeError("No video stream found")

    w, h = video.get("width"), video.get("height")
    rotation = 0
    for sd in video.get("side_data_list") or []:
        if "rotation" in sd:
            rotation = int(sd["rotation"])
    rotation = rotation or int((video.get("tags") or {}).get("rotate", 0) or 0)
    if abs(rotation) % 180 == 90:
        w, h = h, w

    recorded = None
    for tags in (fmt.get("tags") or {}, video.get("tags") or {}):
        tags = {k.lower(): v for k, v in tags.items()}
        # Apple's tag keeps the local timezone, so prefer it.
        for key in ("com.apple.quicktime.creationdate", "creation_time"):
            if key in tags and (recorded := _parse_time(tags[key])):
                break
        if recorded:
            break

    duration = float(fmt.get("duration") or video.get("duration") or 0)
    return {"duration": duration, "width": w, "height": h, "recorded": recorded}


def make_thumb(src: Path, dst: Path, duration: float) -> None:
    seek = f"{min(1.0, duration / 2):.2f}"
    r = _run(["ffmpeg", "-y", "-v", "error", "-ss", seek, "-i", str(src),
              "-frames:v", "1", "-vf", _SHORT_SIDE.format(n=480), "-q:v", "4", str(dst)])
    if r.returncode or not dst.exists():
        raise RuntimeError("Thumbnail failed: " + r.stderr.strip()[-300:])


def make_preview(src: Path, dst: Path) -> None:
    """720p H.264 copy that any browser (incl. phones) can play."""
    tmp = dst.with_suffix(".part.mp4")
    r = _run(["ffmpeg", "-y", "-v", "error", "-i", str(src),
              "-map", "0:v:0", "-map", "0:a:0?",
              "-vf", _SHORT_SIDE.format(n=720) + ",format=yuv420p",
              "-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
              "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", str(tmp)])
    if r.returncode or not tmp.exists():
        tmp.unlink(missing_ok=True)
        raise RuntimeError("Preview failed: " + r.stderr.strip()[-300:])
    tmp.replace(dst)
