# Instructions for AI agents (Claude Code, Codex, Cursor, …)

A user gave you this repository and asked you to install Footage Vault. Keep your messages short and in plain
language, because the user may not be technical. The Google steps happen in the user's own browser (they're signing in to
their own account); everything else you can do.

## 0. Ask which app they want

- **Android app:** phone-only backup to YouTube, no PC needed. Android 11+.
- **PC app:** a Windows tray app with a gallery in the browser, plus people (faces), Takeout import and Syncthing sync.
- **Both:** they share the same YouTube library. Warn them not to back up the same camera folder with both, or clips
  get uploaded twice.

Both need the **Google Cloud project** in step 1.

## 1. Google Cloud project (the user does this; about 15 minutes)

Walk the user through it one step at a time, offering to open each page. Full wording is in `SETUP.md` sections 1–2.

1. **YouTube channel:** sign in at youtube.com and create a channel if needed. Phone-verify it at
   https://www.youtube.com/verify (needed for videos over 15 minutes).
2. **New project:** https://console.cloud.google.com/projectcreate → `Footage Vault`.
3. **API:** https://console.cloud.google.com/apis/library/youtube.googleapis.com → **Enable**.
4. **Consent screen:** https://console.cloud.google.com/auth/overview → Get started. Name `Footage Vault`, the user's
   email as support and contact, audience **External**.
   - **Audience → Test users → add the user's Gmail.** This is required.
   - Optional: **Publish app**, so they aren't signed out every 7 days. If the button is greyed out, skip it.
5. **Keys** at https://console.cloud.google.com/auth/clients → Create client:
   - **PC app:** type **Desktop app** → Download JSON → save as `client_secret.json` in the repo folder
     (`install.ps1` picks up `client_secret_*.json` from Downloads by itself).
   - **Android app:** type **Android**, package `com.footagevault.app`, SHA-1
     `A0:47:5F:E8:32:05:AE:9A:F3:47:34:5A:AF:7A:69:86:E2:49:6B:EC` (the key the published APKs are signed with).
     If you build and sign the APK yourself, use that key's SHA-1 instead.

Never ask the user to paste key contents into the chat, and never print `client_secret.json` or `data\token.json`.

## 2a. Android app

1. Point the user to the latest release: https://github.com/BlueEnd07/footage-vault/releases. On the phone, they
   download `FootageVault.apk`, open it and tap **Install**, allowing installs from the browser if asked.
2. In the app:
   - **Allow access** (pick *Allow all*)
   - **Sign in with Google**, and at "not verified", tap **Continue**
   - Choose **Back up everything** or **Only new videos**
3. It updates itself from Releases (an **Update available** card in the app).

Building it yourself instead: see `docs/development.md` (JDK 17 + Android SDK 35, `gradlew assembleRelease`).
If you sign with a different key, its SHA-1 must be the one registered in step 1.5.

## 2b. PC app (Windows 10/11)

```powershell
git clone https://github.com/BlueEnd07/footage-vault.git "$env:USERPROFILE\footage-vault"
cd "$env:USERPROFILE\footage-vault"
powershell -ExecutionPolicy Bypass -File install.ps1 -NoStart
.\start.bat
```

`install.ps1` installs Python 3.11+ and ffmpeg via winget if missing, sets up `.venv`, installs requirements and
downloads the face models. It's safe to run again. The app runs in the tray at http://localhost:8765 and turns on
start-with-Windows the first time. The user then clicks **⚙ → Connect YouTube** (and **Continue** at "not verified").
Verify it worked:

```powershell
Invoke-RestMethod http://localhost:8765/api/status | Select-Object has_client_secret, connected, auth
```

Optional extras (ask first, one at a time; see `SETUP.md` §4–5):
- **Phone access + notifications (Tailscale):** the user installs Tailscale and enables HTTPS certificates. Then run
  `tailscale serve --bg --https=443 http://127.0.0.1:8765`, after checking `tailscale serve status` so you don't
  replace an existing setup.
- **Wi-Fi phone sync (Syncthing):** use a **dedicated** folder with the ignore patterns from SETUP.md. Never reuse a
  Syncthing folder the user has for other things.

## Rules

- **Privacy:** the user's library is in `data\` (database, previews, face crops, token, PIN) and in the Android
  app's storage. Never upload, paste, commit or summarise it.
- **Don't delete anything for the user:** not videos, YouTube uploads or Syncthing folders. *Free up space* and
  *Delete forever* are for the user to press.
- **Testing changes:** never on the real library. Use `VAULT_DATA=<temp folder>` and port 8799
  (see `docs/development.md`).
- **Restarting the real PC app:** check `counts.uploading` in `/api/status` first.
- **Publishing releases** (`gh release create`) is public and reaches every installed app. Only do it when the user
  asks.

## Troubleshooting

| Problem | Fix |
|---|---|
| `client_secret.json is missing` | Step 1.5, PC. The file must be the **Desktop app** type. |
| Android sign-in fails right away | Missing Android client, or wrong package / SHA-1 (step 1.5). |
| "Access blocked: app has not completed verification" | The user's Gmail isn't in **Test users**. |
| Signed out every 7 days | Testing mode: reconnect, or publish the project. |
| Uploads pause with "daily limit" | YouTube quota. It resumes after midnight Pacific time. |
| Gradle: "Unable to establish loopback connection" | Long TEMP path: set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\short\tmp`. |
| Logs | PC: `data\vault.log`. Android: `adb logcat`. |
