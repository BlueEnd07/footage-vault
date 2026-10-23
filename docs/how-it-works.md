# How Footage Vault works

## YouTube as the storage

Videos are stored as **private** (or unlisted) uploads on the user's own YouTube channel, through the
YouTube Data API v3 and the user's own Google Cloud project. That means each user has their own quota, their own
OAuth consent, and no shared server.

- **Albums** are the channel's **playlists**, private by default. **Favourites** is a private playlist named
  `Favourites · Footage Vault` (Android).
- Every upload's **description** carries the file's details so any device can rebuild the library from YouTube:
  ```
  Original file: VID_20261017_175541984.mp4

  Backed up by Footage Vault
  fv:{"n":"VID_20261017_175541984.mp4","r":1792239941984,"s":62000000,"d":29000,"lat":17.44,"lon":78.35}
  ```
  `n` = file name, `r` = recorded (epoch ms), `s` = size, `d` = duration (ms), plus GPS when available.
  The PC app writes `Original file:` and the title `yyyy-MM-dd HH:mm · <name>`. The Android app writes both forms.
- Because the library lives on YouTube, the **PC app and the Android app see the same videos and albums**. Each
  keeps its own local index: SQLite on the PC (`data/vault.db`), SQLite on the phone (app storage).

### Privacy limits imposed by YouTube

- Uploads from a Google project that hasn't passed **YouTube's API audit** are forced to **private**. This applies
  even if the app asks for unlisted, and those videos can't be changed later. Videos uploaded through the YouTube
  app or website can be switched to unlisted in YouTube Studio.
- YouTube's embedded player **never plays private videos** outside YouTube. Unlisted/public videos play inside the
  apps through YouTube's IFrame player. Private ones open in the YouTube app.
- **Daily quota:** the default is 10,000 units a day. Reading the library is cheap (about 1 unit per 50 videos), and
  playlist edits cost about 50 units each. When the quota runs out, uploads pause until midnight Pacific time.

## Not uploading twice

Before uploading, each app pulls the channel's uploads and matches local files to them by **file name + length**
(±2 s or 1%). File names are read from the `fv:` details, the `Original file:` line or the title. Matches are
marked *backed up* instead of being uploaded. The PC app also fingerprints files (size + first/last 4 MB, or the whole
file if small) to skip copies it already has. Videos "deleted forever" are remembered as tombstones, so a copy still
on a phone isn't imported again.

## Dates

A video's place in the timeline is its best recording time, in this order:
1. `fv:` `r` (exact)
2. The time in the file name or title: `VID_20261017_175541984`, `PXL_…`, `NC_…`, `IMG_…`, DJI `20260924010336`,
   `dji_mimo_20260926_102332_…`, `2026-10-17 17:55`, `Screen Recording 2026-10-17 at 17.55.41`
3. YouTube's `recordingDate`, if it has a time. YouTube usually keeps only the date (00:00 UTC), which is treated
   as a date only.
4. A date-only name (`VID-20261017-WA0012`) or `recordingDate`, placed at midday
5. YouTube's `publishedAt` (the upload time)

Phone files use Android's *date taken*. If it's missing, or differs from a full time in the name by more than 10
minutes (typical for copied or edited files), the name wins. Times in names are read as the phone's/PC's local time.
The same rules are in `android/.../data/TimeGuess.kt` and `vault/timeguess.py`.

**Change date** (Android) sets the time by hand: the item is marked `manual_date` (automatic re-dating skips it)
and, once online, the app rewrites the video's `fv:` `r` and `recordingDate` on YouTube (`videos.update`, 50 units).
Other phones pick it up on their next pull; the PC app applies a description's `fv:` `r` to its own copy of the
video too. Videos whose folder or file name contains "gif" are not listed or uploaded by the Android app.

## Telegram (Android)

Photos (always) and videos up to the chosen length (`Prefs.tgShortMinutes`, default 3) are routed to Telegram by
`data/Router.kt` (column `store` = `telegram` / `youtube`; queued items are re-routed when the setting changes).
`net/Telegram.kt` runs TDLib (Telegram's client library, via `dev.g000sha256:tdl-coroutines-android`) with the
user's own API ID / hash, signs in with phone + code (+ 2FA password), and finds or creates a private channel titled
*Footage Vault* that the account owns (`Prefs.tgChatId`).

Each item is sent as a document (`InputMessageDocument`, content-type detection off, so the file is kept as is)
with a 320 px JPEG preview. The caption mirrors a YouTube description: a date + name line, `Original file: NAME`,
and `fv:{"n","r","s","k","d","w","h","lat","lon"}` (`k` = `photo` / `video`). The message id goes to `tg_msg`, and
the preview is kept in `files/thumbs/` (`thumb`) for the gallery. TDLib's message database is off, so a send that
Android interrupts isn't re-sent later; the half-sent message is deleted and the item retried.

Before **Free up space** deletes a phone copy, `Telegram.verify` re-reads the post (`getMessage`) and checks that
the upload completed with the same size. Items that left the phone are opened with `Telegram.download`
(`downloadFile` into TDLib's cache), then shown or played from that file.

Photos are scanned from MediaStore only when Telegram is set up and photo access (`READ_MEDIA_IMAGES`) is granted.
Uploads to Telegram run before the YouTube queue in the same backup run, with their own lock.

## Google Photos imports (Android)

`ImportActivity` takes `SEND` / `SEND_MULTIPLE` shares (and Settings → Choose videos). It hands the URIs, with the
read grant, to `ImportService`, a foreground service that keeps them usable after the screen closes. The service
copies three at a time to `files/imports/`, adding each as a `copied` item (`uri` = `file:…`, bucket *Google
Photos*). While it runs, `BackupWorker` doesn't upload; when all are copied they become `queued` in one go
(`Importer.recover` does the same after an interrupted import and removes half-copied files). Each item gets its
recording time from the MP4 creation date (and the file name, per the date rules), size, length and GPS read from
the file. A duplicate check (name + size or length) runs before and after copying, and `lastPull` is reset so the
next run matches against the channel first. An album name is kept in `pending_album` and applied once the video has
a YouTube id; the copy is deleted when the item becomes `backed_up`.

## Events and duplicates

- **Events:** clips sorted by time are split wherever the gap is over **3 hours** or consecutive located clips are
  over **50 km** apart. Groups of 2 or more become events, named after the most common place and the date.
- **Duplicates** (Android): two videos are copies when their lengths are within **1 s** *and* they have the same
  file name (ignoring vault title prefixes and `(1)` suffixes), recording times within **3 s**, or the same file
  size. Groups are joined transitively.

## Safety when deleting

Nothing is deleted from a phone or PC unless YouTube has the video:
- **Free up space (Android):** each video is re-checked with YouTube (processed, and the same length), then Android's
  own delete prompt asks for confirmation.
- **PC originals:** these go to the Recycle Bin only after processing is done, the length matches, the file is
  unchanged since import, and a grace period has passed (3 days by default). A failed check keeps the original and
  flags the video under *Problems*.
- **Syncthing folders (PC):** the vault keeps its own hard link (or copy) as soon as a file arrives, so deleting
  it on the phone can't break an upload in progress.
- **Delete forever** is the only action that deletes from YouTube. It always asks first and can't be undone.

## Faces (PC app)

OpenCV's **YuNet** detector and **SFace** recogniser run on the PC. They sample a clip's 720p preview every 1.5 s
(up to 40 frames), keep sharp, confident faces, merge one person's detections within a clip into a track, and file
each track under the person whose 10 closest faces it most resembles (cosine ≥ 0.40). Two faces in the same frame
are never the same person. Face crops and face prints stay in `data/` and are never uploaded.

## Background work

| | PC app | Android app |
|---|---|---|
| Scheduler | Threads in `vault/worker.py` and `vault/faces.py` | WorkManager, every 15 min plus when the app opens |
| Upload | Resumable, 16 MB chunks. The session URL is stored, so it resumes after restarts or a quota pause | Resumable, 8 MB chunks. The session URL is stored, so it resumes after restarts |
| Rules | Pause from the tray; keeps the PC awake while uploading | Wi-Fi only / charging only / pause; progress notification |
| Pull from YouTube | Every 30 min: new videos, re-dating, playlists | Every 30 min, and on pull-to-refresh |

## Network and privacy

- The PC app listens on port 8765. Browsers on the PC get full access. Other devices (same Wi-Fi or Tailscale) need
  the **PIN**, and PC-only settings stay PC-only. Over Tailscale it's reachable only from the user's own tailnet.
- Outside connections: Google/YouTube APIs, YouTube thumbnails and player, OpenStreetMap tiles (map), GitHub
  (Android update check), the push service of the subscribed browser (PC notifications), and OpenCV's model zoo
  (face models, downloaded once).
- No analytics and no third-party servers. Sign-in tokens stay on the device that made them.
