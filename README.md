# Footage Vault

**Your own Google Photos for videos, with YouTube as the storage.**

Footage Vault runs on your Windows PC. It backs up every video from your phone and PC to **your own YouTube
channel as private videos**, then lets you browse them in a fast gallery like Google Photos, on the PC or on your
phone. Once a video is safely on YouTube, the original can be deleted to free up space. The app keeps a small 720p
copy for watching.

Nothing goes to a third-party server. The app runs on your PC and talks only to your Google account.

## What it does

| | |
|---|---|
| **Backup** | Uploads videos to your YouTube channel as **private**. A queue runs in the background, resumes after network drops, and waits out YouTube's daily limit on its own. |
| **Gallery** | Timeline grouped by month and day, a Google Photos-style viewer (swipe between videos, favourites, info), filters and multi-select. |
| **Phone** | Installable phone app (PWA) via Tailscale, push notifications, and automatic Wi-Fi-only backup of the camera folder via Syncthing. |
| **Free up space** | Re-checks each video against YouTube, then deletes it from your phone (via Syncthing) and the PC (to the Recycle Bin). |
| **Albums** | Albums are private YouTube playlists, synced both ways. **Events** group clips recorded less than 3 hours apart automatically. |
| **People** | Face identification **on your PC**: name people, merge, fix mistakes. No faces or images are uploaded. |
| **Map** | Clips on a map with place names, looked up offline (needs the camera's location setting on). |
| **Your YouTube** | Videos you upload to YouTube directly, and your playlists, show up in the vault too. |
| **Delete forever** | Deletes from YouTube permanently, plus local copies, and remembers it so the video never comes back. |
| **Safety** | Originals are deleted only after YouTube has processed the video and its length matches, plus a grace period (default 3 days). Deletes always go to the Recycle Bin. |

## Install

**Requirements:** Windows 10/11, plus a Google account with a YouTube channel. The installer adds Python 3.11+
and ffmpeg if they're missing.

```powershell
git clone https://github.com/BlueEnd07/footage-vault.git
cd footage-vault
powershell -ExecutionPolicy Bypass -File install.ps1
```

The installer sets up everything, downloads the face models and starts the app in the system tray.
After that, open **http://localhost:8765**.

**Then connect your own Google account (one time, about 15 minutes).** YouTube uploads need your own free
Google Cloud key. [SETUP.md](SETUP.md) section 2 walks you through it:
1. Create a Google Cloud project and enable **YouTube Data API v3**.
2. Create an OAuth client of type **Desktop app**.
3. Download it as `client_secret.json` into this folder.
4. In the app, click **⚙ → Connect YouTube**.

**Installing with an AI assistant:** give Claude Code (or another coding agent) the repo link and say
"install this". [AGENTS.md](AGENTS.md) tells it exactly what to do, including which steps need you
(the Google sign-in).

## Everyday use

- **Start / stop:** the app starts with Windows. Right-click the tray icon to **Pause uploads** or **Quit**.
  Run `start.bat` to start it by hand.
- **Phone:** see [SETUP.md](SETUP.md) sections 4–5 for Tailscale (secure address, install as an app,
  notifications) and Syncthing (automatic Wi-Fi backup).
- **Your data** stays in `data\` on your PC: the library database, previews, thumbnails, face crops, your login
  token and PIN. It's excluded from git. Back up `data\vault.db` now and then.

## Good to know

- YouTube re-compresses uploads. The YouTube copy looks good but isn't byte-identical to your original.
- Google projects that haven't been reviewed can only upload **private** videos. That's what this app does anyway.
- While your Google project is in **Testing** mode, Google signs you out every 7 days. Click **Reconnect**
  when the app asks, or publish the project (SETUP.md).
- Music in videos can still get copyright claims, even on private uploads.

## How it's built

Python (FastAPI, SQLite) with a plain HTML/JS front end. No build step.

```
install.ps1          one-command installer (Python, ffmpeg, packages, face models)
start.bat            starts the app (tray icon); `python run.py --console` for a console instead
run.py               entry point
vault/main.py        web server + API
vault/worker.py      background jobs: previews, upload queue, processing checks, safe delete,
                     watched folders, album <-> playlist sync, YouTube import
vault/youtube.py     YouTube Data API v3 (OAuth, resumable upload, playlists)
vault/media.py       ffprobe/ffmpeg: metadata, GPS, thumbnails, previews
vault/faces.py       local face detection + grouping (OpenCV YuNet + SFace)
vault/events.py      automatic events (3-hour gap)
vault/notify.py      Web Push notifications
vault/tray.py        tray icon + start with Windows
web/                 gallery UI, service worker, Leaflet map (bundled)
```

Set `VAULT_DATA` to another folder to try things without touching your real library.
