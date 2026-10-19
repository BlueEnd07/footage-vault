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

Double-click **`start.bat`**. The first run takes a minute to install. The app opens in your browser at
http://localhost:8765.

- Click **⚙ → Connect YouTube** and sign in with the channel's Google account.
  - You'll see **"Google hasn't verified this app"**. That's expected because it's your own app.
    Click **Advanced → Go to Footage Vault (unsafe)**, then allow access.
- Keep the black `start.bat` window open while using it. Closing it stops the app and pauses uploads.

## 4. Use it from your phone

1. Phone and PC must be on the **same Wi-Fi**.
2. On the PC, open **⚙ Settings**. It shows an address like `http://192.168.1.81:8765` and a PIN.
3. Open that address in your phone's browser and enter the PIN.
   Tip: add it to your home screen so it works like an app.
4. Tap **＋ Add videos** and pick videos from your gallery. **Keep the page open** until sending finishes.
5. When a video shows **"✓ Safely on YouTube. You can delete … from your phone"**, delete it
   from your phone yourself. The **Safe to delete from phone** filter lists them all.

The first time, Windows may ask whether Python can use the network. Allow it on **Private networks**.
If the phone can't connect, check that your Wi-Fi network is set to **Private** in Windows settings.
Some office Wi-Fi networks block devices from reaching each other.

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
- If the PC sleeps, uploads pause. They resume when it wakes up.
- An upload interrupted by closing the app starts again from the beginning next time.

## Files

| Path | What it is |
|---|---|
| `data\vault.db` | Library database (dates, YouTube IDs, status) |
| `data\previews\` | 720p copies used for playback in the app |
| `data\thumbs\` | Thumbnails |
| `data\incoming\` | Videos sent from the phone, kept until they're safely on YouTube |
| `data\settings.json` | Port, PIN, grace period |
| `data\token.json` | Your YouTube login (private) |

Back up `data\vault.db` and `data\previews\` now and then. They are the only local record of your library.
