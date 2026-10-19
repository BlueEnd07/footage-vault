# Footage Vault

A local, Google Photos–style library for your videos. YouTube stores them as private uploads.

- Add videos from your phone (same Wi-Fi, PIN-protected) or import folders on the PC.
- Each video is uploaded to your YouTube channel as **private**.
- A 720p preview and a thumbnail stay on the PC, so you can browse a timeline grid and play clips in the app.
- Originals go to the Recycle Bin only after YouTube has finished processing and the length matches, plus a grace period.

Runs on Windows with Python 3.11+ and ffmpeg. Setup, including the Google Cloud / YouTube API steps, is in **[SETUP.md](SETUP.md)**.

```
start.bat            first run creates .venv and installs requirements
vault/main.py        web server + API (FastAPI)
vault/worker.py      background jobs: previews, upload queue, processing checks, safe delete
vault/youtube.py     YouTube Data API v3 (OAuth, resumable upload, status)
vault/media.py       ffprobe/ffmpeg: metadata, thumbnails, previews
web/                 gallery UI (plain HTML/CSS/JS)
```

Set `VAULT_DATA` to another folder to test without touching the real library.
