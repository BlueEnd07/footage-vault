# Footage Vault

A local, Google Photos–style library for your videos. YouTube stores them as private uploads.

- Add videos from your phone (same Wi-Fi, PIN-protected) or import folders on the PC.
- Each video is uploaded to your YouTube channel as **private**.
- A 720p preview and a thumbnail stay on the PC, so you can browse a timeline grid and play clips in the app.
- Originals go to the Recycle Bin only after YouTube has finished processing and the length matches, plus a grace period.
- Runs in the system tray and starts with Windows. It keeps the PC awake while uploading.
- Watched folders: new videos are picked up automatically, e.g. phone videos copied over Wi-Fi by Syncthing.
- Albums are synced to private YouTube playlists.
- Installable phone app (PWA) over Tailscale https, with push notifications ("safe to delete from your phone").

Runs on Windows with Python 3.11+ and ffmpeg. Setup, including the Google Cloud / YouTube API steps, is in **[SETUP.md](SETUP.md)**.

```
start.bat            first run creates .venv and installs requirements
vault/main.py        web server + API (FastAPI)
vault/worker.py      background jobs: previews, upload queue, processing checks, safe delete
vault/youtube.py     YouTube Data API v3 (OAuth, resumable upload, status)
vault/media.py       ffprobe/ffmpeg: metadata, thumbnails, previews
vault/notify.py      Web Push notifications (batched once a minute)
vault/tray.py        tray icon + start-with-Windows (HKCU Run key)
run.py               entry point (tray); `run.py --console` for a console instead
web/                 gallery UI (plain HTML/CSS/JS)
```

Set `VAULT_DATA` to another folder to test without touching the real library.
