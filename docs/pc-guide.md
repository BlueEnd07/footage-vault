# Using the PC app

The PC app runs at http://localhost:8765, or on your phone through Tailscale. Set it up first with
[SETUP.md](../SETUP.md). It looks and works like Google Photos:
- **PC:** a sidebar on the left (Videos, Library, Map, plus Favourites, Uploading, Safe to delete and Problems).
- **Phone:** a bottom bar.

## Adding videos

- **Upload → From this device**: pick files. On a phone, keep the page open until sending finishes.
- **Upload → Import a PC folder**: every video in it and its subfolders. Originals go to the Recycle Bin once
  they're safely on YouTube.
- **Settings → Back up from folders**: folders that are watched for new videos, e.g. a Syncthing phone folder.
- **Videos you uploaded to YouTube yourself** show up by themselves (every 30 minutes, or **Settings → Sync now**).
  They play from YouTube until you give them a copy here with **Import YouTube Takeout** (below).

The **status pill** at the top shows **Backed up**, **N left**, **Paused**, **Daily limit** or **problems**. Click it
for details.

## Gallery and viewer

- Videos sit under **Today / Yesterday / day** headers. Tick a day's circle to select the whole day.
- **Tile badges:** a cloud or ring while backing up, a red **!** if it failed, **♥** for favourites.
- **Select** (✓ in the top bar, or long-press on a phone) gives **Favourite**, **Add to album** and **Delete forever**.
- **Viewer:**
  - ←/→ keys or swipes move between videos, and **i** toggles Info.
  - The top bar has **Open on YouTube** (full quality), Favourite, Info, Add to album and Delete.
  - **Info** shows people, date, file, place, backup status, albums and a YouTube Studio link.
- Videos play from a 720p copy kept on the PC. Videos that only live on YouTube play from YouTube; private ones
  only play on YouTube itself.

## Library

- **Favourites, Uploading, Safe to delete, Problems:** quick lists.
- **People:** faces found **on this PC** (nothing is uploaded) and grouped by person.
  - Tap someone, then **Add name**.
  - **Same person as…** merges two people. **✕** on a face means "not this person".
  - **Hide** removes someone from the list.
  - People seen in only one clip are left out.
- **Events:** clips less than 3 hours (and 50 km) apart. **Save as album** keeps one.
- **Albums** are YouTube playlists, synced both ways.
  - **Share** sends the playlist link. A private album becomes **unlisted** first, after asking.
  - **Rename** and **Delete album** (the videos stay).

## Map

Clips with GPS are shown where they were shot, with the nearest town found offline. Map tiles come from
OpenStreetMap. Turn on location in your camera's settings to get new clips on the map.

## Free up space on the phone

When phone videos are safely on YouTube, a **Free up X GB** card appears.
- **Synced with Syncthing:** one click deletes them from the PC's sync folder, and Syncthing removes them from the
  phone at the next Wi-Fi sync. Each video is checked against YouTube first.
- **Sent by hand (＋):** these can't be deleted from the phone by a website. Delete them in the phone's gallery,
  then click **I've deleted these myself**.

## Delete forever

Select videos (or open one) and choose **Delete**. This deletes them **from YouTube permanently** and from the
vault. With the box ticked, it also deletes any copy still on the PC (Recycle Bin) or phone (via Syncthing).
Deleted videos are remembered, so they're never imported or uploaded again.

## Import YouTube Takeout

This gives videos you uploaded to YouTube directly a local copy, so they play like the others (Settings → *Videos
uploaded to YouTube directly*):
1. At **takeout.google.com**:
   - **Deselect all**, then tick **YouTube and YouTube Music**.
   - Under *All YouTube data included*, keep only **videos** and **video metadata**.
2. **Create export**. When it's ready, download and unzip it.
3. Click **Import YouTube Takeout** and paste the unzipped folder's path.

Each file is matched to its video by title and length. Nothing is uploaded again. The Takeout files then follow
the usual grace period to the Recycle Bin, so keep the zip if you want the originals.

## Settings

| Setting | |
|---|---|
| YouTube | Connect / Disconnect, Sync now |
| Videos uploaded to YouTube directly | The Takeout steps and the Import button |
| Notifications on this device | On / Test / Off (needs the https Tailscale address on a phone) |
| Open on your phone | Tailscale address, same-Wi-Fi address, PIN |
| Back up from folders | Watched folders, and a one-time folder import |
| Deleting originals | Grace period before originals go to the Recycle Bin (default 3 days) |

## How originals are deleted

An original is moved to the Recycle Bin only when **all** of these are true:
- YouTube has finished processing the video.
- Its length on YouTube matches the file.
- The file hasn't changed since it was imported.
- The grace period has passed.

If a check fails, the video goes to **Problems** and the original is kept.
