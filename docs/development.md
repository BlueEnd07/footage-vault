# Development

## Repository

```
android/                      Android app (Kotlin 2.3, Jetpack Compose, Material 3; minSdk 30, targetSdk 35)
  app/src/main/java/com/footagevault/app/
    MainActivity.kt           navigation, top/bottom bars, selection, system prompts
    YouTubePlayerActivity.kt  full-screen YouTube IFrame player with swipe between videos
    VaultApp.kt               app setup: image loader, notification channels, backup schedule
    data/                     Db (SQLite), Prefs, MediaScanner (MediaStore), TimeGuess (dates)
    net/                      Auth (Google Identity), YouTube (Data API over OkHttp), Updater (GitHub Releases)
    work/                     BackupWorker (WorkManager), Sync (YouTube <-> local), Notify
    ui/                       screens: grid, viewer, library, map, settings, duplicates, theme
vault/                        PC app (Python 3.11+, FastAPI, SQLite)
  main.py                     HTTP API, PIN gate, app shell
  worker.py                   previews, uploads, processing checks, safe delete, watched folders,
                              album <-> playlist sync, YouTube import, Takeout import, delete forever
  youtube.py                  YouTube Data API (google-api-python-client), resumable upload, playlists
  media.py                    ffprobe/ffmpeg: metadata, GPS, thumbnails, previews; offline place names
  faces.py                    face detection and grouping (OpenCV YuNet + SFace)
  events.py, timeguess.py     events (3-hour gap) and recording dates
  notify.py                   Web Push (batched once a minute)
  tray.py                     tray icon, start with Windows
web/                          PC front end: index.html, style.css, app.js, icons.js, sw.js, vendor/leaflet
```

## PC app

```powershell
powershell -ExecutionPolicy Bypass -File install.ps1 -NoStart   # .venv, packages, face models
.venv\Scripts\python run.py --console                            # run in a console instead of the tray
```

- **Never test on your real library.** Point the app at a scratch folder and a different port:
  ```powershell
  $env:VAULT_DATA = "$env:TEMP\fv-test"; mkdir $env:VAULT_DATA
  '{"port": 8799, "autostart_initialized": true}' | Set-Content "$env:VAULT_DATA\settings.json"
  .venv\Scripts\python run.py --console
  ```
  Without a token in that folder it can't touch YouTube. To test YouTube-facing logic, replace the functions in
  `vault.youtube` with fakes, as the inline tests in the history do.
- **Database:** `vault/db.py` holds `SCHEMA` plus additive migrations in `init()`. Only add tables or columns:
  users' databases are upgraded in place.
- **Restarting a running app:** check `GET /api/status` → `counts.uploading` first. A PC upload interrupted
  mid-way resumes from its saved YouTube session; if the session expired it starts again.
- **Front end:** plain JS, no build step. Icons are inline SVG paths in `web/icons.js`. Check the syntax with
  `node --check web/app.js` and `node --check web/sw.js`.
- **Tests:** `python -m pip install -r requirements-dev.txt`, then `.venv\Scripts\python -m pytest -q`. Tests set
  their own `VAULT_DATA` to a temp folder, so they never touch the real library.

## Android app

Requirements: **JDK 17** and the **Android SDK** (platform 35, build-tools 35). Android Studio is optional.

```powershell
cd android
# local.properties (not committed):  sdk.dir=C\:/path/to/android-sdk
.\gradlew.bat assembleRelease       # -> app/build/outputs/apk/release/app-release.apk
```

- **Windows gotcha:** if Gradle fails with *"Unable to establish loopback connection"*, the temp folder path is too
  long for Java's Unix-domain sockets. Set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\short\tmp`.
- **Tests:** `.\gradlew.bat :app:testDebugUnitTest` (JUnit + MockWebServer; no phone needed).
- **Signing and Google sign-in:**
  - Release builds are signed with the dedicated key in `android/footage-vault-release.jks`, configured by
    `android/keystore.properties`. Both are git-ignored: back them up, or future updates can't be installed.
  - The key's **SHA-1** must be registered on the Android OAuth client in Google Cloud. See `docs/release.md`.
  - Without those two files the build falls back to the debug key, which is only for development.
- The release build uses R8 (`isMinifyEnabled`) to strip unused code; the current APK is about 30 MB. Keep rules
  are in `app/proguard-rules.pro`.
- **No client secret** is in the app: Google Identity Services recognises the app by package name + SHA-1.

## Releasing the Android app

The app updates itself from this repo's GitHub Releases. `net/Updater.kt` looks for the release whose tag is
`android-v<versionCode>` with the highest number, and an attached `.apk`. It refuses an update unless the APK's
SHA-256 matches the release asset's digest, the package name matches, and it is signed with the installed key.

1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle.kts`.
2. Commit and push.
3. Build, verify and publish:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\release-android.ps1 -Publish
   ```
   Without `-Publish` it only builds and checks the signature/checksum into `dist\`.
4. Check: `https://api.github.com/repos/<owner>/<repo>/releases` lists the new tag (it can lag a minute), and the
   downloaded asset matches the build.

Installed apps show **Update available** the next time they open. APK files are git-ignored. They're only
distributed through Releases. See `docs/release.md` for the one-time key setup.

## Conventions

- Plain, user-facing wording in the UI. Explain why something can't be done (for example private videos and
  YouTube's player).
- Anything destructive asks first, and checks YouTube before deleting a local copy.
- Nothing from `data/`, `client_secret*.json`, `token.json`, `models/`, `local.properties`, keystores or APKs is
  committed.
