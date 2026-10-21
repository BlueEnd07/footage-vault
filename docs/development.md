# Development

## Repository

```
android/                      Android app (Kotlin 2.1, Jetpack Compose, Material 3; minSdk 30, targetSdk 35)
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
  mid-way starts again from 0.
- **Front end:** plain JS, no build step. Icons are inline SVG paths in `web/icons.js`. Check the syntax with
  `node -e "new Function(require('fs').readFileSync('web/app.js','utf8'))"`.

## Android app

Requirements: **JDK 17** and the **Android SDK** (platform 35, build-tools 35). Android Studio is optional.

```powershell
cd android
# local.properties (not committed):  sdk.dir=C\:/path/to/android-sdk
.\gradlew.bat assembleRelease       # -> app/build/outputs/apk/release/app-release.apk
```

- **Windows gotcha:** if Gradle fails with *"Unable to establish loopback connection"*, the temp folder path is too
  long for Java's Unix-domain sockets. Set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\short\tmp`.
- **Signing and Google sign-in:**
  - The release build is signed with the debug keystore (`~/.android/debug.keystore`), and its **SHA-1** must be
    registered as the Android OAuth client in the Google Cloud project.
  - If you sign with another key, register that key's SHA-1 instead. `keytool -list -v -keystore <file>` prints it.
  - Losing the keystore means future builds can't update installed copies or sign in until a new SHA-1 is
    registered and the app is reinstalled.
- The release build uses R8 (`isMinifyEnabled`), which shrinks the APK from about 66 MB to about 5 MB. Keep rules
  are in `app/proguard-rules.pro`.
- **No client secret** is in the app: Google Identity Services recognises the app by package name + SHA-1.

## Releasing the Android app

The app updates itself from this repo's GitHub Releases. `net/Updater.kt` looks for the release whose tag is
`android-v<versionCode>` with the highest number, and an attached `.apk`.

1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle.kts`.
2. Build: `.\gradlew.bat assembleRelease`, and copy the APK to `FootageVault.apk`.
3. Commit and push.
4. Publish:
   ```powershell
   gh release create android-v<versionCode> FootageVault.apk --title "Footage Vault for Android <versionName>" --notes-file notes.md
   ```
5. Check: `https://api.github.com/repos/<owner>/<repo>/releases` lists the new tag (it can lag a minute), and the
   downloaded asset matches the build.

Installed apps show **Update available** the next time they open. APK files are git-ignored. They're only
distributed through Releases.

## Conventions

- Plain, user-facing wording in the UI. Explain why something can't be done (for example private videos and
  YouTube's player).
- Anything destructive asks first, and checks YouTube before deleting a local copy.
- Nothing from `data/`, `client_secret*.json`, `token.json`, `models/`, `local.properties`, keystores or APKs is
  committed.
