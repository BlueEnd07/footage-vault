"""People: finds faces in each clip and groups them by person, entirely on this PC.

Each clip's 720p preview is sampled every 1.5 s. YuNet finds faces and SFace turns each
one into a 128-number faceprint. Faceprints of one person within a clip are merged into a
single face row, which is then matched against everyone seen before. No image or faceprint
ever leaves the PC; only the two models are downloaded once (from OpenCV's model zoo).
"""
import ctypes
import hashlib
import logging
import os
import threading
import time
import urllib.request

import numpy as np

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")  # OpenCV 5 warns about unused DNN targets
try:
    import cv2
except ImportError:  # requirements not installed yet
    cv2 = None

from . import db
from .config import FACES, MODELS, PREVIEWS

log = logging.getLogger("vault")

_ZOO = "https://github.com/opencv/opencv_zoo/raw/main/models/"
DETECTOR = "face_detection_yunet_2023mar.onnx"
RECOGNIZER = "face_recognition_sface_2021dec.onnx"
MODEL_URLS = {DETECTOR: _ZOO + "face_detection_yunet/" + DETECTOR,
              RECOGNIZER: _ZOO + "face_recognition_sface/" + RECOGNIZER}

# Tuned on ~60 real phone / action-cam clips. Wrongly merging two people is worse than
# splitting one (splits are fixed with "Same person as…"), so the thresholds lean strict.
STEP = 1.5            # seconds between sampled frames
MAX_FRAMES = 40       # per clip; longer clips are sampled further apart
MIN_SCORE = 0.9       # YuNet confidence
MIN_SIZE = 48         # face width in the 720p preview, px
MIN_SHARP = 60        # Laplacian variance of the aligned face; below this it's motion blur or too dark
SAME_IN_CLIP = 0.5    # cosine to the running mean of a face track within one clip
SAME_PERSON = 0.40    # mean cosine to the person's 10 closest faces (SFace's own cut-off is 0.363)
CROP_PX = 160

FACE_CROPS = FACES  # data/faces/{face id}.jpg, served at /media/faces

# models: "unknown" | "downloading" | "ok" | "missing";  pending: clips with a preview still to check;
# running/done/total: progress shown as "Finding faces… done/total clips"
state = {"models": "unknown", "error": None, "pending": 0, "running": False, "done": 0, "total": 0}
lock = threading.Lock()  # faces/persons changes: the analysis thread vs. rename/merge/delete


# ---------- models (downloaded once, then loaded from models/) ----------

MAX_MODEL = 200 << 20
MODEL_SHA256 = {
    DETECTOR: "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    RECOGNIZER: "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
}


def _sha256(p) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _model_ok(p, name: str | None = None) -> bool:
    """A real ONNX file starts with protobuf field 1 (0x08); a Git LFS pointer is text.
    The checksum also rejects a tampered or half-written file."""
    try:
        if p.stat().st_size <= 100_000:
            return False
        with open(p, "rb") as f:
            if f.read(1) != b"\x08":
                return False
        return _sha256(p) == MODEL_SHA256.get(name or p.name)
    except OSError:
        return False


def ensure_models() -> bool:
    if cv2 is None or not hasattr(cv2, "FaceDetectorYN"):
        state.update(models="missing", error="opencv-python-headless isn't installed (pip install -r requirements.txt)")
        return False
    MODELS.mkdir(exist_ok=True)
    for name, url in MODEL_URLS.items():
        p = MODELS / name
        if _model_ok(p):
            continue
        state["models"] = "downloading"
        log.info("Downloading face model %s (one time) from %s", name, url)
        tmp = p.with_suffix(".part")
        try:
            total = 0
            with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
                while chunk := r.read(1 << 20):
                    total += len(chunk)
                    if total > MAX_MODEL:
                        raise RuntimeError("download is far too large")
                    f.write(chunk)
            if not _model_ok(tmp, name):
                got = _sha256(tmp) if tmp.exists() else "nothing"
                raise RuntimeError(f"check failed (got {got[:12]}…)")
            os.replace(tmp, p)
        except Exception as e:
            tmp.unlink(missing_ok=True)
            log.warning("Face models missing, People is paused: %s", e)
            state.update(models="missing", error=f"Couldn't download the face models ({e}). Retrying later.")
            return False
    state.update(models="ok", error=None)
    return True


def _load():
    cv2.setNumThreads(1)  # background job: one core, leave the rest for previews/uploads
    det = cv2.FaceDetectorYN.create(str(MODELS / DETECTOR), "", (320, 320), MIN_SCORE, 0.3, 5000)
    rec = cv2.FaceRecognizerSF.create(str(MODELS / RECOGNIZER), "")
    return det, rec


# ---------- one clip ----------

def _frames(path, duration: float):
    """(frame number, seconds, image) every STEP seconds, at most MAX_FRAMES."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise RuntimeError("can't open preview")
    try:
        fps = cap.get(cv2.CAP_PROP_FPS) or 30
        step = max(STEP, (duration or 0) / MAX_FRAMES)
        i, n, nxt = 0, 0, 0.0
        while n < MAX_FRAMES and cap.grab():
            t = i / fps
            i += 1
            if t + 1e-6 < nxt:
                continue
            nxt += step
            ok, img = cap.retrieve()
            if not ok:
                break
            n += 1
            yield n, t, img
    finally:
        cap.release()


def _crop(img, box) -> np.ndarray:
    """Square face picture with some margin, kept inside the frame where possible."""
    H, W = img.shape[:2]
    x, y, w, h = (float(v) for v in box[:4])
    side = int(min(max(w, h) * 1.7, W, H))
    x0 = int(min(max(x + w / 2 - side / 2, 0), W - side))
    y0 = int(min(max(y + h / 2 - side / 2, 0), H - side))
    return cv2.resize(img[y0:y0 + side, x0:x0 + side], (CROP_PX, CROP_PX), interpolation=cv2.INTER_AREA)


def _detect(det, rec, path, duration: float) -> list[dict]:
    """Sharp, frontal-enough faces in the clip, one dict per detection."""
    found = []
    for n, t, img in _frames(path, duration):
        det.setInputSize((img.shape[1], img.shape[0]))
        _, faces = det.detect(img)
        for f in faces if faces is not None else []:
            score, size = float(f[14]), int(f[2])
            if score < MIN_SCORE or size < MIN_SIZE:
                continue
            aligned = rec.alignCrop(img, f)
            sharp = cv2.Laplacian(cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
            if sharp < MIN_SHARP:
                continue
            emb = rec.feature(aligned).flatten().astype(np.float32)
            found.append({"frame": n, "t": t, "emb": emb / np.linalg.norm(emb), "size": size,
                          "score": score * min(1.0, sharp / 200), "crop": _crop(img, f)})
    return found


def _tracks(found: list[dict]) -> list[dict]:
    """Merges detections of the same person within one clip. Two faces in the same
    frame are always different people."""
    tracks = []
    for d in found:
        best, best_sim = None, SAME_IN_CLIP
        for tr in tracks:
            if d["frame"] in tr["frames"]:
                continue
            sim = float(tr["sum"] @ d["emb"]) / float(np.linalg.norm(tr["sum"]))
            if sim >= best_sim:
                best, best_sim = tr, sim
        if best is None:
            tracks.append({"sum": d["emb"].copy(), "frames": {d["frame"]}, "best": d})
        else:
            best["sum"] += d["emb"]
            best["frames"].add(d["frame"])
            if d["score"] * min(d["size"], 160) > best["best"]["score"] * min(best["best"]["size"], 160):
                best["best"] = d
    for tr in tracks:
        tr["emb"] = (tr["sum"] / np.linalg.norm(tr["sum"])).astype(np.float32)
    return tracks


def _known() -> tuple[np.ndarray, np.ndarray]:
    """(person id per face, faceprints) of every face already grouped, hidden people included
    so that someone hidden stays hidden."""
    rs = db.rows("SELECT person_id, embedding FROM faces WHERE person_id IS NOT NULL")
    if not rs:
        return np.zeros(0, np.int64), np.zeros((0, 128), np.float32)
    return (np.array([r["person_id"] for r in rs], np.int64),
            np.stack([np.frombuffer(r["embedding"], np.float32) for r in rs]))


def _best_person(emb, pids, embs, exclude: set) -> int | None:
    """Mean similarity to each person's 10 closest faces (unlike a plain average/centroid,
    this doesn't drift as a person collects many different-looking faces)."""
    if not len(pids):
        return None
    sims = embs @ emb
    best, best_sim = None, SAME_PERSON
    for pid in np.unique(pids):
        if int(pid) in exclude:
            continue
        s = float(np.sort(sims[pids == pid])[-10:].mean())
        if s >= best_sim:
            best, best_sim = int(pid), s
    return best


def _save(vid: int, tracks: list[dict]) -> int:
    """Stores one face per track and assigns each to a person (new or known)."""
    with lock:
        if not db.row("SELECT 1 FROM videos WHERE id=?", (vid,)):
            return 0  # deleted while we were looking at it
        pids, embs = _known()
        used: dict[int, set] = {}  # person -> frames, so two faces in one frame stay two people
        touched = set()
        for tr in tracks:
            exclude = {p for p, frames in used.items() if frames & tr["frames"]}
            pid = _best_person(tr["emb"], pids, embs, exclude)
            if pid is None:
                pid = db.run("INSERT INTO persons (created_at) VALUES (?)", (db.now(),))
            b = tr["best"]
            fid = db.run("INSERT INTO faces (video_id, person_id, t, score, size, embedding) VALUES (?,?,?,?,?,?)",
                         (vid, pid, round(b["t"], 2), b["score"], b["size"], tr["emb"].tobytes()))
            cv2.imwrite(str(FACES / f"{fid}.jpg"), b["crop"], [cv2.IMWRITE_JPEG_QUALITY, 88])
            pids = np.append(pids, pid)
            embs = np.vstack([embs, tr["emb"]])
            used.setdefault(pid, set()).update(tr["frames"])
            touched.add(pid)
        for pid in touched:
            _set_cover(pid)
        db.run("UPDATE videos SET faces_checked=1 WHERE id=?", (vid,))
    return len(tracks)


# ---------- background thread ----------

def _low_priority():
    if os.name == "nt":
        THREAD_PRIORITY_IDLE = -15
        ctypes.windll.kernel32.SetThreadPriority(ctypes.windll.kernel32.GetCurrentThread(), THREAD_PRIORITY_IDLE)


def _next_clip() -> dict | None:
    """Newest clip not checked yet whose preview exists (originals may be gone)."""
    todo = [v for v in db.rows("""SELECT id, name, duration FROM videos WHERE faces_checked=0 AND status != 'new'
                                  ORDER BY recorded_at DESC""") if (PREVIEWS / f"{v['id']}.mp4").exists()]
    done = db.row("SELECT COUNT(*) n FROM videos WHERE faces_checked=1")["n"]
    state.update(pending=len(todo), running=bool(todo), done=done, total=done + len(todo))
    return todo[0] if todo else None


def _loop():
    _low_priority()
    models = None
    while True:
        try:
            if models is None:
                if not ensure_models():
                    time.sleep(600)
                    continue
                models = _load()
            v = _next_clip()
            if not v:
                time.sleep(10)
                continue
            try:
                n = _save(v["id"], _tracks(_detect(*models, PREVIEWS / f"{v['id']}.mp4", v["duration"])))
                log.info("Faces: %s -> %d", v["name"], n)
            except Exception as e:  # unreadable preview: don't retry forever
                log.warning("Faces skipped %s: %s", v["name"], e)
                db.run("UPDATE videos SET faces_checked=1 WHERE id=?", (v["id"],))
            time.sleep(0.2)
        except Exception:
            log.exception("Face analysis error")
            time.sleep(60)


def start():
    threading.Thread(target=_loop, daemon=True, name="faces").start()


def progress() -> dict:
    checked = db.row("SELECT COUNT(*) n FROM videos WHERE faces_checked=1")["n"]
    total = max(state["total"], checked)  # fixed while a batch runs, so the count only goes up
    return {"checked": checked, "total": total, "done": checked, "running": checked < total and state["models"] == "ok",
            "models_ok": state["models"] == "ok", "models": state["models"], "error": state["error"]}


# ---------- people (list + user actions) ----------

def people() -> list[dict]:
    """People worth showing: named, or seen in at least 2 clips. Hidden ones never."""
    return db.rows("""
        SELECT p.id, p.name, p.cover_face, COUNT(DISTINCT f.video_id) AS videos
        FROM persons p JOIN faces f ON f.person_id = p.id
        WHERE p.hidden = 0 GROUP BY p.id
        HAVING p.name IS NOT NULL OR COUNT(DISTINCT f.video_id) >= 2
        ORDER BY p.name IS NULL, videos DESC, p.id""")


def _set_cover(pid: int) -> None:
    """The person's picture: their largest sharp face."""
    db.run("""UPDATE persons SET cover_face = (SELECT id FROM faces WHERE person_id=?
                                               ORDER BY score * MIN(size, 160) DESC LIMIT 1) WHERE id=?""", (pid, pid))


def _drop_empty_people() -> None:
    db.run("DELETE FROM persons WHERE id NOT IN (SELECT person_id FROM faces WHERE person_id IS NOT NULL)")


def rename(pid: int, name: str) -> None:
    db.run("UPDATE persons SET name=?, hidden=0 WHERE id=?", (name.strip()[:80] or None, pid))


def hide(pid: int) -> None:
    db.run("UPDATE persons SET hidden=1 WHERE id=?", (pid,))


def merge(pid: int, into: int) -> None:
    """All of pid's faces become into's; into keeps its name (or takes pid's if it has none)."""
    with lock:
        if pid == into or len(db.rows("SELECT id FROM persons WHERE id IN (?,?)", (pid, into))) != 2:
            raise ValueError("Person not found")
        db.run("UPDATE persons SET name = COALESCE(name, (SELECT name FROM persons WHERE id=?)) WHERE id=?", (pid, into))
        db.run("UPDATE faces SET person_id=? WHERE person_id=?", (into, pid))
        db.run("DELETE FROM persons WHERE id=?", (pid,))
        _set_cover(into)


def remove_face(fid: int) -> int:
    """"Not this person": the face gets a person of its own. That person stays out of
    People unless they turn up in another clip (or are named)."""
    with lock:
        f = db.row("SELECT person_id FROM faces WHERE id=?", (fid,))
        if not f:
            return 0
        new = db.run("INSERT INTO persons (created_at) VALUES (?)", (db.now(),))
        db.run("UPDATE faces SET person_id=? WHERE id=?", (new, fid))
        _set_cover(new)
        if f["person_id"]:
            _set_cover(f["person_id"])
        _drop_empty_people()
        return new


def forget_video(vid: int) -> None:
    """Deleted forever: its faces, their pictures, and people left with no faces."""
    with lock:
        rs = db.rows("SELECT id, person_id FROM faces WHERE video_id=?", (vid,))
        db.run("DELETE FROM faces WHERE video_id=?", (vid,))
        for r in rs:
            (FACES / f"{r['id']}.jpg").unlink(missing_ok=True)
        _drop_empty_people()
        for pid in {r["person_id"] for r in rs if r["person_id"]}:
            _set_cover(pid)
