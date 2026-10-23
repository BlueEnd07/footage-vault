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
- **Privacy of new uploads:** *Private* (only you) or *Unlisted* (anyone with the link). YouTube makes uploads from
  apps that haven't passed its API audit private. With *Unlisted* chosen, the app asks YouTube to switch each upload
  to unlisted once it's finished, and stops asking if YouTube refuses.
- **Make unlisted:** select videos → **⋮ → Make unlisted**, or **Settings → Privacy → Make N private videos
  unlisted**. Unlisted videos play inside the app. Each change uses about 50 of YouTube's 10,000 daily units. If
  YouTube refuses, the app shows the route that always works: YouTube Studio → Content → select all → Edit →
  Visibility → Unlisted.
- The status pill at the top shows the state: **Backed up**, **N left**, **N waiting** (for Wi-Fi), **Paused**,
  **Daily limit** or **problems**.
- **GIFs are skipped.** WhatsApp and keyboard "GIFs" are saved as tiny videos (for example in a *WhatsApp Animated
  Gifs* folder). Anything with "GIF" in its folder or file name is not shown and not uploaded. GIFs uploaded by older
  versions stay on YouTube until you delete them.

## Photos and short videos on Telegram

Long videos go to YouTube. **Photos and short videos** can go to a **private channel in your own Telegram
account**, as the original files: no re-encoding, no YouTube daily limit, and one gallery for both.

Set it up once in **Settings → Telegram · photos and short videos**:
1. Open **my.telegram.org**, sign in with your phone number, tap **API development tools** and create an app (any
   name and short name). Copy **App api_id** and **App api_hash** into the card, then tap **Connect**. They're
   stored only on the phone.
2. Enter your phone number, then the login code Telegram sends you (and your two-step verification password, if
   you have one).
3. The app finds or makes a private channel called **Footage Vault**, owned by you. Tap **Allow photos** so it can
   read your photos.
4. Choose which videos go to Telegram: none, or up to 1, 3 (default), 5 or 10 minutes long.

- Photos and short videos from your backup folders upload in the background, under the same Wi-Fi / charging rules.
  Each one is posted to the channel as a file, with its name, date and GPS in the caption.
- In the gallery they sit with your videos. A small paper-plane mark means "on Telegram"; photos open full size with
  pinch-to-zoom. Info says *Backed up to Telegram (original file)*.
- **Delete everywhere** removes the Telegram post too.
- **Free up space** includes Telegram items: each post is checked first (still there, whole file). Afterwards
  the gallery keeps a preview, and opening one downloads the original from Telegram (it's cached, so the second
  time is instant). **Open in Telegram** is there if loading fails.
- Other devices and the PC app don't show Telegram items yet.
- **Disconnect Telegram** signs out on this phone. The channel and its files stay in your Telegram account.
- Telegram's rules don't promise to keep files forever, so don't treat it as the only copy of anything irreplaceable.

## From Google Photos

Videos already in Google Photos can be moved to YouTube through the share sheet:
1. In Google Photos, open an album (or any view), select the photos and videos and tap **Share → Footage Vault**.
   **Settings → Back up from Google Photos** has the same steps, an **Open Google Photos** button, and
   **Choose videos**, which opens Android's photo picker (it shows Google Photos videos and albums too when the
   phone's picker is linked to Google Photos).
2. Optionally type an **album** name, or tap one of your albums. Tap **Back up**.
3. You can close the screen. The videos are downloaded into the app in the background, three at a time, with a
   progress notification. **Uploads wait until every video is downloaded**, so the two don't share the connection.
   Then they upload like phone videos, under the same Wi-Fi / charging rules, with their **file name, recording date
   and GPS** from the files (GPS only if the shared file still has it). With an album name they go into that album
   (a YouTube playlist, made if it doesn't exist). **Library → Uploading** shows how it's going.
4. The app's copies are deleted once YouTube has finished processing them.

- Videos already in your library (same file name and size or length) are skipped, and the app checks your channel
  before uploading, so nothing goes up twice.
- Photos go to Telegram, so connect it first (Settings → Telegram); otherwise they wait. GIFs are left out.
- Google Photos downloads cloud-only videos before sharing them, so you need free space for the batch. Large
  batches are best shared a few at a time, on Wi-Fi.
- Pasting an album link isn't possible: Google no longer lets apps read Google Photos albums or share links, so
  selecting and sharing is the way in. For a whole library, use the PC app's [Takeout import](pc-guide.md).

## Gallery

- **Videos** tab: everything on your phone and on your channel, newest first, under Today / Yesterday / day headers.
- **Pinch** with two fingers to change the tile size. Zoom all the way out for the **month view**.
- **Big tiles:** at the two roomiest sizes, a busy day shows some tiles big (2×2), like Google Photos. Videos and
  favourites are picked first. A big video that's on the phone plays by itself, silently and on a loop, after a
  moment.
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
- **Delete** from the viewer: the photo or video shrinks away and the next one takes its place.
- **Swipe left/right** for the next or previous video (also inside the YouTube player). **Swipe down** to close.
- **Double-tap** the left or right side to skip 10 seconds.
- Long videos **resume** where you stopped.
- The bottom bar has **Share**, **Favourite**, **Edit** (photos), **Info** (date, place, size, backup status),
  **Add to** (album) and **Delete**.
- **Share** sends the files themselves: photos as images, videos as video files. Ones that are only in your backup
  are fetched first. A video that lives only on YouTube is shared as its link.
- **Photos:** pinch or double-tap to zoom. A photo that's only on Telegram shows its preview at once and sharpens when
  the original has loaded; the photos next to it load ahead, so swiping is instant.
- **Edit** (photos): **Crop** (free, original, 1:1, 4:5, 16:9, 9:16) and **Rotate**, **Adjust** (brightness,
  contrast, saturation, warmth) and **Filters** (Vivid, Warm, Cool, B&W, Fade, Punch). **Save copy** saves a new
  photo in the Camera folder with the same date and place, so it sits next to the original and is backed up too.
  The original is never changed.
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

### Change date

Clips that arrive later (say, trip videos sent on WhatsApp on the 19th from a trip on the 17th) can be moved to the
right day:
- Long-press to select the videos, tap **Change date** (calendar icon) and pick the day. Or open a video, tap
  **Info**, then the calendar icon next to its date.
- **Keep their times** keeps each video's own time of day, so they stay in order. **Set a time…** gives them all one
  time (a second apart, in their current order).
- The new date is saved on YouTube (in the video's details and its recording date), so it shows on every phone and
  in the PC app. Automatic date fixing never changes a date you set; Info shows it as *set by you*.

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
