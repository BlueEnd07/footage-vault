# Setting up the PC app (Windows)

The PC app runs in the system tray. It uploads videos to your YouTube channel and keeps a 720p copy of each one on
the PC for watching. Originals go to the Recycle Bin once they're safely on YouTube. It's for Windows 10/11.

> Only need backup from your phone? The **[Android app](docs/android.md)** does that on its own, without a PC.
> You can still use both: they share the same YouTube library.

Using the app once it's set up: **[docs/pc-guide.md](docs/pc-guide.md)**.

## 1. YouTube channel (one time)

1. Pick the Google account to use. A separate account just for this is safest.
2. Sign in at https://www.youtube.com. If there's no channel yet, create one (profile picture → **Create a channel**).
3. Verify the channel with your phone number at https://www.youtube.com/verify.
   Without this, videos longer than 15 minutes can't be uploaded.

## 2. Google Cloud project (one time, about 15 minutes)

This gives Footage Vault permission to upload to **your** channel, under your own free quota.

1. Go to https://console.cloud.google.com and sign in **with the same account**.
2. **Select a project → New project**. Name it `Footage Vault`, then click **Create**. Make sure it's selected at the top.
3. **APIs & Services → Library**. Search **YouTube Data API v3**, then click **Enable**.
4. **Google Auth Platform** (or **OAuth consent screen**) → **Get started**:
   - App name `Footage Vault`. Use your email as the support email and the contact.
   - Audience **External**.
5. **Audience → Test users → Add users:** add your own Gmail address. *Required*: without it, sign-in is blocked.
6. Optional: **Audience → Publish app** (*In production*). This stops Google signing you out every 7 days.
   - No review is needed. You'll just see a "Google hasn't verified this app" screen at sign-in.
   - If **Publish app** is greyed out, fill in everything on the **Branding** page first. If it's still greyed out,
     skip this step: Testing mode works fine, and you just reconnect once a week.
7. **Clients → Create client → Desktop app → Create → Download JSON.**
8. Save the file as **`client_secret.json`** in the footage-vault folder. `install.ps1` finds it in Downloads automatically.

> Keep `client_secret.json` and `data\token.json` private. Never share them or put them online.
> The `.gitignore` already keeps them out of git.

## 3. Install and start

```powershell
cd footage-vault
powershell -ExecutionPolicy Bypass -File install.ps1
```

The installer adds Python and ffmpeg if they're missing, installs everything else, downloads the face models and
starts the app. Later, start it with **`start.bat`**. It also starts with Windows (you can switch that off in the tray menu).

- Footage Vault sits in the **tray** near the clock (maybe under **^**). Left-click opens it; right-click gives
  **Pause uploads**, **Start with Windows** and **Quit**.
- Open http://localhost:8765 and click **⚙ → Connect YouTube**:
  1. Pick the channel's account.
  2. Google says **"Google hasn't verified this app"**. That's expected for your own app: click **Continue**.
  3. Tick the permission box, then click **Continue**.
- The PC stays awake while it's uploading. If it's shut down, uploads pause and continue later.

## 4. Open it on your phone, from anywhere (Tailscale)

Tailscale gives your phone a private, secure **https** address for the PC, on Wi-Fi or mobile data. Only your own
devices can reach it. You need https to install the web app and get its notifications.

1. Install **Tailscale** on the PC and the phone, and sign in with the same account on both.
2. At https://login.tailscale.com/admin/dns, make sure **MagicDNS** is on, and under **HTTPS Certificates**
   click **Enable HTTPS**.
3. On the PC, run this. First check `tailscale serve status`, so you don't replace something you already serve.
   ```powershell
   tailscale serve --bg --https=443 http://127.0.0.1:8765
   ```
4. **⚙ Settings → Open on your phone** shows the address (`https://<your-pc>.<your-tailnet>.ts.net`) and the **PIN**.
5. On the phone:
   - Open that address in **Chrome** and enter the PIN.
   - Tap **Install** (or **⋮ → Add to Home screen → Install**).
   - In the app, open **⚙ → Notifications → Turn on**, then **Send a test**.

On the same Wi-Fi, the plain address `http://<PC's IP>:8765` also works, but without install or notifications.
The first time, allow Python on **Private networks** in Windows Firewall.

## 5. Automatic Wi-Fi backup from the phone (Syncthing), optional

Use this if you want the **PC app** to back up your phone. The **Android app** can do the same without a PC.
**Don't run both on the same camera folder**, or the same clip can be uploaded twice.

**PC:** install Syncthing from https://syncthing.net/downloads/ ("Syncthing Windows Setup"), then open
http://127.0.0.1:8384. Make an empty folder, e.g. `%USERPROFILE%\Videos\Phone`.

**Phone:** install **Syncthing-Fork** (Play Store or F-Droid). Then:
1. **Settings → Run conditions:** turn on **Run only when connected to Wi-Fi**.
2. **Devices → ＋:** scan the PC's ID (on the PC, **Actions → Show ID**), then accept the phone on the PC.
3. **Folders → ＋:**
   - Path `DCIM/Camera`
   - **Folder type: Send & Receive**, so **Free up space** can delete from the phone
   - Share it with the PC
4. In that folder's **Ignore patterns**, add these so only videos are copied:
   ```
   (?i).trashed-*
   (?i)!*.mp4
   (?i)!*.mov
   (?i)!*.3gp
   (?i)!*.mkv
   *
   ```

**PC again:**
1. Accept the folder. Set its path to the folder you made, and **Folder type: Send & Receive**.
2. Put the same ignore patterns in a `.stignore` file in that folder.
3. In Footage Vault: **⚙ → Back up from folders → Add phone sync folder**, then paste the path.

**Don't delete files in that PC folder by hand.** With two-way sync, that deletes them on the phone too.
Use **Free up space** instead: it checks YouTube first.

## Files

| Path | What it is |
|---|---|
| `client_secret.json` | Your Google key (private) |
| `data\vault.db` | Library database: dates, YouTube IDs, albums, people, status |
| `data\token.json` | Your YouTube sign-in (private) |
| `data\previews\`, `data\thumbs\` | 720p copies and thumbnails used in the app |
| `data\faces\` | Face pictures for People (made on this PC) |
| `data\incoming\` | Videos sent from the phone, kept until they're safely on YouTube |
| `data\settings.json` | Port, PIN, grace period, watched folders |
| `data\vault.log` | Log file. Look here if something goes wrong |

Back up `data\` now and then (especially `vault.db` and `previews\`). Videos stay on YouTube regardless, but
the library's organisation (albums, people, favourites) and the in-app copies live here.

## Troubleshooting

| Problem | Fix |
|---|---|
| "client_secret.json is missing" | Step 2.7–2.8. The file must be the **Desktop app** type. |
| "Access blocked: app has not completed verification" | Your Gmail isn't in **Test users** (step 2.5). |
| Asked to reconnect every week | The project is in Testing mode. Reconnect, or publish the project (step 2.6). |
| Uploads paused: "daily limit" | YouTube's free daily quota. It resumes by itself after midnight Pacific time. |
| Phone can't open the page | Use Tailscale (step 4), or allow Python on Private networks in Windows Firewall. |
| Videos over 15 minutes fail | The channel isn't phone-verified (step 1.3). |
