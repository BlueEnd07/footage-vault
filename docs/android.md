# Footage Vault for Android

Backs up your phone's videos straight to your own YouTube channel, with no PC and no server. It has a Google
Photos-style gallery on top. It needs Android 11 or newer.

## Install

### 1. Google Cloud: add an Android key (one time)

Use the same Google Cloud project as the PC app, or create one: follow [SETUP.md](../SETUP.md) steps 1 and
2.1–2.6 (channel, project, YouTube Data API, consent screen, your Gmail as a test user). Then:

1. Open **Google Auth Platform → Clients → Create client**.
2. Choose application type **Android**.
3. Enter these values:
   - **Package name:** `com.footagevault.app`
   - **SHA-1 certificate fingerprint:** the fingerprint of the key the app is signed with. For the APKs on this
     repo's [Releases](https://github.com/BlueEnd07/footage-vault/releases) page it's
     `A0:47:5F:E8:32:05:AE:9A:F3:47:34:5A:AF:7A:69:86:E2:49:6B:EC`. If you build the app yourself with your own key,
     use your key's SHA-1 ([development.md](development.md)).
4. Click **Create**. You don't need to download anything, and the app contains no secret.

### 2. Install the app

1. On the phone, download **`FootageVault.apk`** from the latest release.
2. Open it and tap **Install**. Android asks once to allow installs from your browser; allow it.
3. Open Footage Vault:
   - **Allow access** to videos (choose *Allow all*). Location access is used for the map, and notifications for
     backup updates.
   - **Sign in with Google**: pick the account that owns your channel. At *"Google hasn't verified this app"*,
     tap **Continue**, then allow YouTube access.
   - Choose **Back up everything** or **Only new videos from now on**. Videos already on your channel (for example,
     uploaded by the PC app) are recognised by name and length, and aren't uploaded again.

### Updates

The app checks this repo's Releases. When a new version is out, an **Update available** card appears on the
Videos screen: tap **Update**, then **Install**. The first time, Android asks you to allow Footage Vault to install
apps; switch it on, go back and tap Update again. You can also check by hand in **Settings → App version**.

## Backup

- New videos in the folders you picked (**Settings → Folders to back up**, *Camera* by default) are uploaded in the
  background about every 15 minutes, and right away when you open the app.
- **Only on Wi-Fi** is on by default. **Only while charging** and **Pause backup** are in Settings.
- Uploads continue where they stopped, even after the app or phone restarts.
- A progress notification shows while uploading. When videos are safely backed up you get
  **"Free up X GB"**, and if something fails, **"Upload problem"**.
- **Privacy of new uploads:** *Private* (only you) or *Unlisted* (anyone with the link). Unlisted only works once
  your Google project has passed YouTube's API audit; until then YouTube makes uploads private anyway, and the app
  tells you.
- The status pill at the top shows the state: **Backed up**, **N left**, **N waiting** (for Wi-Fi), **Paused**,
  **Daily limit** or **problems**.

## Gallery

- **Videos** tab: everything on your phone and on your channel, newest first, under Today / Yesterday / day headers.
- **Pinch** with two fingers to change the tile size. Zoom all the way out for the **month view**.
- **Date scroller:** while scrolling, grab the handle on the right edge to jump through months.
- **Badges on tiles:**
  - **red cloud**: not backed up yet
  - **red !**: backup failed
  - **ring**: uploading
  - **♥**: favourite
- **Select:** long-press a video, or tap a day's circle to select the whole day. The top bar then offers
  **Favourite**, **Share**, **Add to album** and **Delete**.
- **Pull down** to refresh from YouTube.

## Watching

- **Tap** a video to open it. It grows out of its tile.
  - **On the phone:** plays right there.
  - **Unlisted / public, only on YouTube:** plays straight away in YouTube's player inside the app.
  - **Private, only on YouTube:** YouTube only plays these in its own app, so you get **Play on YouTube**.
    Make videos unlisted in YouTube Studio to play them inside Footage Vault.
- **Swipe left/right** for the next or previous video (also inside the YouTube player). **Swipe down** to close.
- **Double-tap** the left or right side to skip 10 seconds.
- Long videos **resume** where you stopped.
- The bottom bar has **Share**, **Favourite**, **Info** (date, place, size, backup status), **Add to** (album)
  and **Delete**.
- In the **YouTube player**, touch the screen to show ← back, **♡ Favourite** and **🗑 Delete** at the top.

## Deleting

**Delete** (selection bar, viewer, or the YouTube player) asks what you want:
- **Delete from phone:** frees space and keeps the YouTube copy. Videos still processing on YouTube are kept
  until they're done.
- **Delete everywhere** (or **Delete from YouTube**): removes the video from your channel permanently, and from
  the phone too. Android asks to confirm the phone part.

A phone video whose YouTube copy you deleted is marked **don't back up**, so it isn't uploaded again by itself.

## Library

- **Favourites, Uploading, Free up space, Problems:** quick lists.
- **Duplicates:** copies of the same video side by side, so you can delete the extras.
  - **Copies** means the same length, plus the same file name, recording time or size.
  - **Delete on phone:** removes that phone copy. Android asks first.
  - **Delete on YouTube:** permanently deletes that copy from your channel.
- **Events:** clips recorded less than 3 hours apart, grouped automatically. Tap one, then **Save as album**.
- **Albums:** these are your channel's playlists. Changes go both ways.
  - **New album:** pick a name. It's created as a private playlist.
  - **Add to album:** select videos (or open one), then tap **Add to**. A video can be added once it's backed up.
  - **Share:** opens the album and sends its YouTube playlist link. A private album is made **unlisted** first,
    after asking. Private videos inside it won't play for others, and the app tells you how many there are.
  - **Rename / Delete album:** deleting an album deletes the playlist, never the videos.
- **Map** tab: clips where they were shot. This needs the camera's location setting (Camera → Settings →
  Location / Geotag), so older clips without it aren't on the map.

## Free up space

**Free up space** (the card on the Videos screen, the Library list, or **Free up** in the viewer) does this:
1. It re-checks every video against YouTube: processed, and the same length.
2. Only those that pass are offered.
3. Android shows its own **"Delete N videos?"** prompt. Nothing is deleted without it.

Afterwards the videos stay in your gallery and play from YouTube.

## Dates

Each video is dated by when it was really shot, from (in order):
1. Footage Vault's own saved details
2. The time in the file name (`VID_20261017_175541984`, `PXL_…`, `NC_…`, DJI / `dji_mimo_…`, `IMG_…`, screen recordings)
3. YouTube's recording time
4. A date-only name such as WhatsApp's `VID-20261017-WA0012`, placed at midday
5. The upload date, as a last resort

## Settings

| Setting | |
|---|---|
| YouTube | Sign in / out |
| Theme | Same as phone, Light, Dark, **Pure black** (OLED) |
| Backup | Only on Wi-Fi, Only while charging, Pause backup, Back up older videos too, Back up now |
| Privacy of new uploads | Private / Unlisted |
| Folders to back up | Any gallery folder with videos (Camera, DJI, Screen recordings, …) |
| App version | Check for updates / Update |

## Troubleshooting

| Problem | Fix |
|---|---|
| Sign-in fails straight away | The Android key is missing or has the wrong package / SHA-1 (Install, step 1). |
| "Access blocked" at sign-in | Your Gmail isn't a **Test user** in the Google project. |
| "Backup paused, sign in again" notification | Testing-mode sign-in expired (every 7 days). Open the app and sign in, or publish the Google project. |
| Nothing uploads | Check the pill: *waiting* means no Wi-Fi (or not charging, if that's on). Also check **Folders to back up**. |
| YouTube video shows a black screen or won't play | Private videos only play in the YouTube app. Make it unlisted in YouTube Studio, then pull to refresh. |
| A video is on the wrong day | It has no time in its name or details. It's placed by the best date available (see Dates). |
| Same clip uploaded twice | You're backing up the same folder from two places (e.g. the PC app's Syncthing and this app). Use one. Clean up in **Library → Duplicates**. |
