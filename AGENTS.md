# Instructions for AI agents (Claude Code, Codex, Cursor, …)

A user gave you this repository and asked you to install Footage Vault on their PC. Follow these steps in order.
Everything is automated except the Google steps, which the user must do in their own browser: they're signing in
to their own account. Keep your messages to the user short and in plain language. They may not be technical.

## 0. Check the platform

- **Windows 10/11 only** (the tray icon, Recycle Bin and start-with-Windows features are Windows-specific).
  On macOS/Linux, stop and tell the user it isn't supported yet.
- The repository should be cloned somewhere the user owns, e.g. `%USERPROFILE%\footage-vault`:
  ```powershell
  git clone https://github.com/BlueEnd07/footage-vault.git "$env:USERPROFILE\footage-vault"
  ```
  If git is missing: `winget install --id Git.Git -e --scope user`, or download the ZIP from GitHub and unzip it.

## 1. Run the installer (automatic)

```powershell
cd "$env:USERPROFILE\footage-vault"
powershell -ExecutionPolicy Bypass -File install.ps1 -NoStart
```

It installs Python 3.11+ and ffmpeg via winget if missing, creates `.venv`, installs `requirements.txt`,
downloads the face models (~40 MB) and looks for `client_secret.json`. It's safe to run again.
Exit code 0 means installed; read its last lines to see whether the Google key is still missing.
If winget asks for confirmation or admin rights, tell the user to accept it.

## 2. Google / YouTube key (the user does this; about 15 minutes)

The app uploads with the **user's own** free Google Cloud OAuth key. There's no shared key. Walk the user through
it one step at a time. Full wording is in `SETUP.md` sections 1–2. Offer to open each page for them:

1. **YouTube channel:** sign in at https://www.youtube.com with the account to use. Create a channel if there isn't
   one. Phone-verify it at https://www.youtube.com/verify (needed for videos longer than 15 min).
2. **New project:** https://console.cloud.google.com/projectcreate → name `Footage Vault` → Create.
3. **Enable the API:** https://console.cloud.google.com/apis/library/youtube.googleapis.com → **Enable**.
4. **Consent screen:** https://console.cloud.google.com/auth/overview → Get started.
   App name `Footage Vault`, the user's email for support and contact, audience **External**.
   - **Audience → Test users → Add users:** add the user's own Gmail address. **Required**, otherwise sign-in is blocked.
   - Optional: **Publish app** (In production) avoids being signed out every 7 days. If Publish is greyed out, skip
     it; Testing mode works fine.
5. **Key:** https://console.cloud.google.com/auth/clients → Create client → **Desktop app** → Create → **Download JSON**.
6. Re-run `install.ps1 -NoStart`. It finds `client_secret_*.json` in Downloads and copies it in as `client_secret.json`.
   Or copy it yourself into the repo folder under that name. Check that it contains an `"installed"` key.

Don't ask the user to paste the key's contents into the chat, and never print `client_secret.json` or `data\token.json`.

## 3. Start and connect

```powershell
cd "$env:USERPROFILE\footage-vault"; .\start.bat
```

The app runs in the system tray and opens http://localhost:8765. It also turns on start-with-Windows on first run;
the user can switch that off in the tray menu. Then the user clicks **⚙ → Connect YouTube** and signs in.
Google shows **"Google hasn't verified this app"**. That's expected for a personal key, so they click
**Continue**, tick the permission box, and **Continue** again.

Verify:

```powershell
Invoke-RestMethod http://localhost:8765/api/status | Select-Object has_client_secret, connected, auth
```

You want `has_client_secret=True`, `connected=True`, `auth=ok`. Tell the user they can now add videos
(**＋** on the phone, **Import PC folder** on the PC).

## 4. Optional extras (ask first, one at a time)

- **Phone app + notifications (Tailscale):** see `SETUP.md` §4. The user installs Tailscale on PC and phone and
  enables **HTTPS certificates** at https://login.tailscale.com/admin/dns. Then run:
  `tailscale serve --bg --https=443 http://127.0.0.1:8765`.
  First check `tailscale serve status`, so you don't replace something the user already serves.
- **Automatic Wi-Fi backup from the phone (Syncthing):** see `SETUP.md` §5. Use a **dedicated** sync folder for the
  phone's camera videos, with the ignore patterns given there. Never point it at an existing Syncthing folder the
  user already uses for other things. Add the folder in the app under **⚙ → Watched folders → ＋ Phone sync folder**.

## Rules

- **Privacy:** the user's library lives in `data\` (database, previews, face crops, token, PIN). Never upload, paste,
  commit or summarise its contents. `.gitignore` already excludes `data\`, `client_secret*.json`, `token.json`
  and `models\`.
- **Don't delete anything for the user:** not videos, not YouTube uploads, not Syncthing folders. The app's own
  **Free up space** and **Delete forever** are for the user to press.
- **Testing changes:** never experiment on the real library. Run a copy with
  `$env:VAULT_DATA="<temp folder>"; .venv\Scripts\python run.py --console` and set `"port": 8799` in that folder's
  `settings.json`, so it doesn't clash with the real app on 8765.
- **Restarting the real app:** check `GET /api/status` → `counts.uploading` first. An interrupted upload starts
  again from 0.

## Troubleshooting

| Problem | Fix |
|---|---|
| `client_secret.json is missing` | Step 2.5 / 2.6. The file must be the **Desktop app** type. |
| "Access blocked: app has not completed verification" | The user's Gmail isn't in **Test users** (step 2.4). |
| Signed out every 7 days | The project is in Testing mode. Click **Reconnect YouTube**, or publish the project. |
| `quotaExceeded` / uploads pause | Daily YouTube API limit. It resumes by itself after midnight Pacific time. |
| Phone can't open the page | Same Wi-Fi + allow Python on **Private** networks in Windows Firewall, or use Tailscale. |
| Videos longer than 15 min fail | The channel isn't phone-verified (step 2.1). |
| Logs | `data\vault.log` |
