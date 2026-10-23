# Footage Vault

**Your own Google Photos for videos, with your YouTube channel as the storage.**

Footage Vault backs up every video you shoot to **your own YouTube channel**, as private or unlisted
videos. You browse them in a gallery that looks and feels like Google Photos. Once a video is safely on YouTube,
you can free up space on your phone with one tap. There are two apps, and you can use either or both:

| | **Android app** (no PC needed) | **PC app** (Windows) |
|---|---|---|
| Backs up | the phone's camera folders, in the background, on Wi-Fi | phone videos (sent or synced), PC folders |
| Gallery | timeline grid, pinch to zoom, date scroller, swipe viewer | timeline grid, viewer, people, map |
| Albums | private/unlisted YouTube playlists, both ways | same |
| Extras | Photos and short videos on Telegram (original files), Free up space, Google Photos import, change date, duplicates, themes, in-app updates | face identification (on the PC), Takeout import, notifications, tray app |

Both apps read and write **the same YouTube library**, so everything shows up in both. Nothing goes to any server
except YouTube, and only to your own channel. On Android you can also send **photos and short videos to a private
channel in your own Telegram account** as the original files ([docs/android.md](docs/android.md)).

## Features

- **Automatic backup.** Uploads resume after a dropped connection, and the queue waits out YouTube's daily limit
  by itself. Videos already on your channel are recognised and not uploaded twice.
- **A gallery like Google Photos.** It has Today / Yesterday / day headers, a month view and a red badge on anything
  not backed up yet. Tap to play, and swipe between videos.
- **Correct dates.** Clips are dated by when they were shot, using their file name and saved details, so the same
  day stays together even for videos uploaded straight to YouTube.
- **Albums, Events and Favourites.** Albums are YouTube playlists, and you can **share an album** as a playlist link.
  Events group clips recorded less than 3 hours apart. Favourites are a private playlist.
- **People** (PC app). Faces are found and grouped **on your PC**, and you name them.
- **Map.** Clips appear where they were shot, if the camera saved the location.
- **Free up space.** Each video is re-checked against YouTube before the phone copy is deleted. Nothing is deleted
  without your confirmation.
- **Duplicates.** Copies of the same video are shown side by side so you can delete the extras.
- **Privacy.** Everything stays on your devices and your own YouTube channel. Your Google login stays on your devices.

## Get started

- **Android app:** see **[docs/android.md](docs/android.md)**. In short, download `FootageVault.apk` from
  [Releases](https://github.com/BlueEnd07/footage-vault/releases), add an Android key in your Google Cloud project,
  install it and sign in. After that, it updates itself.
- **PC app (Windows):** see **[SETUP.md](SETUP.md)**. In short:
  ```powershell
  git clone https://github.com/BlueEnd07/footage-vault.git
  cd footage-vault
  powershell -ExecutionPolicy Bypass -File install.ps1
  ```
- **Installing with an AI assistant:** give Claude Code (or another coding agent) the repo link and say "install this".
  [AGENTS.md](AGENTS.md) tells it exactly what to do and which steps need you.

Both apps need a **free Google Cloud project with the YouTube Data API**, so uploads run under your own account
and quota. The setup takes about 15 minutes once and is covered step by step.

## Documentation

| Doc | What's in it |
|---|---|
| [docs/android.md](docs/android.md) | Installing and using the phone app: backup, gallery gestures, albums, free up space, duplicates, updates |
| [SETUP.md](SETUP.md) | Setting up the PC app: Google key, Tailscale (phone access), Syncthing (Wi-Fi sync) |
| [docs/pc-guide.md](docs/pc-guide.md) | Using the PC app: gallery, people, map, albums, Takeout, delete forever, settings |
| [docs/how-it-works.md](docs/how-it-works.md) | How it works: YouTube as storage, dates, duplicate detection, safety checks, limits |
| [docs/development.md](docs/development.md) | Building, testing and releasing both apps |
| [docs/release.md](docs/release.md) | The Android release key, its SHA-1, and publishing a release |
| [AGENTS.md](AGENTS.md) | Install instructions for AI coding agents |

## Good to know

- **YouTube re-compresses videos.** The YouTube copy looks good but isn't byte-identical to the original.
  Keep originals elsewhere if you need them exactly.
- **Private vs unlisted.** Uploads from your own Google project are **private** until the project passes YouTube's
  API audit. Private videos only play in the YouTube app/site. Unlisted ones also play inside Footage Vault.
  You can switch videos to unlisted in YouTube Studio.
- **Sign-in every 7 days** while the Google project is in *Testing* mode. Publish it to avoid this (see SETUP.md).
- **Copyright claims:** music in videos can still be claimed by YouTube, even on private uploads.

## Repository layout

```
android/          Android app (Kotlin, Jetpack Compose)
vault/            PC app back end (Python, FastAPI, SQLite)
web/              PC app front end (HTML/CSS/JS, no build step)
docs/             Documentation
tests/            PC tests (pytest; use a temp VAULT_DATA)
install.ps1       One-command installer for the PC app
start.bat         Starts the PC app in the system tray
```

## License

MIT — see [LICENSE](LICENSE).
