# Footage Vault — setup

A local "Google Photos" for your videos. Each video is uploaded to your YouTube channel
as a **private** video. A 720p copy stays on this PC so you can watch it in the app. The original
is deleted once it's safely on YouTube.

## 1. YouTube channel (one time)

1. Pick the Google account you want to use. A separate account just for this is safest.
2. Open https://www.youtube.com and sign in. If you don't have a channel yet, create one
   (profile picture → **Create a channel**).
3. Verify the channel with your phone number at https://www.youtube.com/verify.
   Without this, videos longer than 15 minutes can't be uploaded.

## 2. Google Cloud project (one time, about 15 minutes)

This gives the app permission to upload to your channel. It's free.

1. Go to https://console.cloud.google.com and sign in **with the same account**.
2. At the top, choose **Select a project → New project**. Name it `Footage Vault` and click **Create**.
3. Turn on the API: **APIs & Services → Library**, search **YouTube Data API v3**, then click **Enable**.
4. Set up the sign-in screen: **APIs & Services → OAuth consent screen** (it may be called **Google Auth Platform**).
   - User type: **External**
   - App name: `Footage Vault`. Use your email for both the support email and the developer contact.
   - Under **Audience / Test users**, add your own Google account.
5. **Publish the app.** On the same page, click **Publish app** so the status changes to **In production**.
   This matters: while it's left in "Testing", Google logs you out every 7 days.
   You don't need to submit it for review. You'll only see a warning screen when you sign in.
6. Create the login key: **APIs & Services → Credentials → Create credentials → OAuth client ID**.
   - Application type: **Desktop app**
   - Name: `Footage Vault`
   - Click **Create**, then **Download JSON**.
7. Rename the downloaded file to **`client_secret.json`** and put it in this folder,
   next to `start.bat`.

> Keep `client_secret.json` and `data\token.json` private. Don't share them or upload them anywhere.

## 3. Start the app

Double-click **`start.bat`**. The first run takes a minute to install. After that, Footage Vault runs as a
**tray icon** (bottom-right, near the clock; it may be hidden under **^**). There's no window to keep open.

- **Left-click** the icon to open the app. **Right-click** it to see upload status, **Pause uploads**,
  turn **Start with Windows** on or off, or **Quit**.
- It starts with Windows automatically. The PC stays awake while an upload is running.
- First time: click **⚙ → Connect YouTube** and sign in with the channel's Google account.
  You can do this from the phone too. After you tap **Allow**, Google ends on a "This site can't be reached" page.
  Copy that page's address and paste it into the box Footage Vault shows.
  - You'll see **"Google hasn't verified this app"**. That's expected because it's your own app.
    Click **Continue** (or **Advanced → Go to Footage Vault**), tick the permission box, then click **Continue**.
  - If the app says **Reconnect YouTube**, click it. That happens when the app needs a new permission
    (albums need one), or every 7 days while the Google project is in Testing mode.

## 4. Phone app with Tailscale (works anywhere, installable, notifications)

Tailscale gives your phone a private, secure (https) address for the PC that works on Wi-Fi or mobile data.
Phones only allow installing a web app and getting its notifications over https.

**One time, in the Tailscale admin page:**
1. Open https://login.tailscale.com/admin/dns and sign in.
2. Make sure **MagicDNS** is on.
3. Under **HTTPS Certificates**, click **Enable HTTPS**.
4. Tell Claude it's on. Claude runs `tailscale serve --bg 8765` on the PC. That one command publishes the vault at
   `https://<your-pc>.<your-tailnet>.ts.net` (also shown in ⚙ Settings), reachable only from your own Tailscale devices.

**On the phone:**
1. Install **Tailscale** from the Play Store, sign in with the same account and switch it on.
2. Open the https address in **Chrome** and enter the PIN (shown in ⚙ Settings on the PC).
3. Tap **Install** in the banner, or use **⋮ → Add to Home screen → Install**. Footage Vault now has its own app icon.
4. In the app, open **⚙ → Turn on notifications** and allow them. Use **Send a test** to check.

You'll get a notification when:
- videos arrive from the phone
- videos are **safe to delete from your phone**
- something failed
- the daily YouTube limit is reached
- the YouTube sign-in needs renewing

## 5. Automatic phone backup, Wi-Fi only (Syncthing)

The app can't read your gallery in the background, so a small free sync app does that part.
It copies new camera videos to the PC **only on Wi-Fi**, and the vault picks them up from there.

**PC:**
1. Install **Syncthing** for Windows from https://syncthing.net/downloads/ (use "Syncthing Windows Setup"),
   then open its web page at http://127.0.0.1:8384.
2. Make an empty folder for phone videos, e.g. `%USERPROFILE%\Videos\Phone`.

**Phone (Android):**
1. Install **Syncthing-Fork** from the Play Store or F-Droid. It's the maintained Android version of Syncthing.
2. In Syncthing-Fork: **Settings → Run conditions** →
   - turn on **Run only when connected to Wi-Fi**
   - turn off **Run on mobile data**
3. **Devices → ＋**: scan the PC's QR code. On the PC page, click **Actions → Show ID**. Accept the phone on the PC.
4. **Folders → ＋**:
   - Folder path: your camera folder, usually `DCIM/Camera`
   - **Folder type: Send & Receive**. This lets the **Free up space** button delete videos from the phone.
   - Share it with the PC.
   - Optional safety net: **File versioning → Trash Can, clean out after 7 days**. Videos deleted this way
     stay in a hidden `.stversions` folder for a week before the space is freed.
   - Open the folder's **Ignore patterns** and paste these, so only videos are copied:
     ```
     (?i)!*.mp4
     (?i)!*.mov
     (?i)!*.3gp
     (?i)!*.mkv
     *
     ```

**Back on the PC:**
1. In Syncthing, accept the shared folder.
   - Set its path to the folder you made, e.g. `%USERPROFILE%\Videos\Phone`.
   - **Folder type: Send & Receive**. Footage Vault only deletes files here when you tap **Free up space**.
2. In Footage Vault: **⚙ → Watched folders → ＋ Phone sync folder**, and paste that same path.

**How it fits together:**
- You record → you get home to Wi-Fi → Syncthing copies the video to the PC → the vault adds it → it's uploaded to YouTube.
- You get a notification: **"Safe to delete from your phone."**
- Open the app and tap **Free up space**. It re-checks every video against YouTube, then deletes them from
  the sync folder on the PC. Syncthing deletes them on the phone at its next Wi-Fi sync. Photos are never touched.
- You can also delete videos on the phone yourself. The vault keeps its own copy as soon as a video arrives,
  so deleting it on the phone at any point can't break an upload.
- **Don't delete or move files in the PC sync folder by hand.** With Send & Receive, that would delete them on the phone too.

### Manual upload (any time, no Syncthing needed)

In the app, tap **＋** and pick videos. On the same Wi-Fi you can also use the local address shown in
⚙ Settings (e.g. `http://192.168.x.x:8765`). Keep the page open until sending finishes.

## Albums

- In the app, tap **Select** (or long-press a video), pick videos, then choose **Add to album**.
- You can also open a video and tap **＋ Album**.
- Every album is also a **private playlist** on your YouTube channel. Renaming or deleting an album
  does the same to the playlist. Deleting an album never deletes videos.

## Events and Map

- **Events** (top of the Albums tab) are made automatically from clips recorded less than **3 hours** apart,
  even across midnight. Clips more than 50 km apart are split into separate events.
  Tap an event, then **Save as album** to keep it as an album / YouTube playlist.
- **Map** shows clips where they were recorded, with the nearest town as the place name. Place names are
  looked up offline on the PC; only the map's background tiles load from OpenStreetMap.
  This needs the camera to save location: on the phone, open **Camera → Settings → Location / Geotag** and turn it on.
  Older videos recorded without it can't be placed.

## People (face identification)

- Footage Vault finds faces in every clip and groups the same person together. It runs **entirely on this PC**:
  no picture or face data is uploaded anywhere. The two small face models are downloaded once from OpenCV.
- Find them at the top of **Albums → People**. Tap someone, then **Add name**.
- If one person shows up twice, open one of them and tap **Same person as…**.
- If a wrong face got mixed in, open the person and tap **✕** on that face.
- Strangers seen in only one clip are left out of the list.
- Videos uploaded straight to YouTube aren't scanned, because there's no copy on the PC to look at.

## Watching videos

- Swipe **left/right** for the next or previous video, **down** to close, and **up** for Info.
  On the PC, use the arrow keys or the ‹ › buttons.
- The bottom bar has **♡ Favourite**, **ⓘ Info**, **＋ Add to** (album) and **🗑 Delete**.
  ▶ at the top opens the video in full quality on YouTube.
- The **♥ Favourites** filter is on the Videos tab.

## Videos you uploaded to YouTube yourself

Footage Vault checks your channel every 30 minutes, or right away with **⚙ → Sync from YouTube**:
- Videos uploaded directly to YouTube (private, unlisted or public) appear in the gallery and play from YouTube.
- Every playlist on the channel becomes an album. Changes made on YouTube (renames, removed videos,
  deleted playlists) carry over to the vault, and changes made in the vault carry over to YouTube.

## Delete forever

Open a video and tap **Delete forever…**, or select several videos and tap **Delete**.
This deletes the video **from YouTube permanently**, which can't be undone, and removes it from Footage Vault.
With the box ticked, it also deletes any copy still on the PC (to the Recycle Bin) or on the phone (via Syncthing).
Deleted videos are remembered, so they're never imported or uploaded again.

## How deleting works

- The app only deletes an original after **all** of these are true:
  - YouTube has finished processing the video.
  - The video's length on YouTube matches the file.
  - The file hasn't changed since it was imported.
- It then waits for a grace period (default **3 days**, change it in ⚙), so you can check the video first.
- Files go to the **Recycle Bin**, not permanent delete. Empty the bin whenever you like.
- If a check fails, the video is marked **Problems** and the original is kept.
- **Import PC folder** works the same way: once each video is safely on YouTube, its original in that
  folder goes to the Recycle Bin.

## Things to know

- **YouTube's copy is compressed.** It looks good but isn't identical to your original file.
- **Upload limits:** Google limits how many uploads a day the free API allows. When the limit is reached,
  the app pauses and continues automatically after the daily reset (around 12:30 PM India time).
- **Copyright claims:** songs or music in a video can still get claimed by YouTube, even when it's private.
- The PC stays awake while an upload is running. If it's shut down, or sleeps because the lid is closed,
  uploads pause and continue when it's back.
- An upload interrupted by closing the app starts again from the beginning next time.

## Files

| Path | What it is |
|---|---|
| `data\vault.db` | Library database (dates, YouTube IDs, albums, status) |
| `data\vault.log` | Log file. Look here if something goes wrong |
| `data\vapid_private.pem` | Key used to send notifications (private) |
| `data\previews\` | 720p copies used for playback in the app |
| `data\thumbs\` | Thumbnails |
| `data\incoming\` | Videos sent from the phone, kept until they're safely on YouTube |
| `data\settings.json` | Port, PIN, grace period |
| `data\token.json` | Your YouTube login (private) |

Back up `data\vault.db` and `data\previews\` now and then. They are the only local record of your library.
