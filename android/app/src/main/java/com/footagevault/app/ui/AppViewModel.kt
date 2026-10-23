package com.footagevault.app.ui

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footagevault.app.data.Db
import com.footagevault.app.data.Item
import com.footagevault.app.data.MediaScanner
import com.footagevault.app.data.Prefs
import com.footagevault.app.data.Router
import com.footagevault.app.net.ApiException
import com.footagevault.app.net.Auth
import com.footagevault.app.net.Telegram
import com.footagevault.app.net.Updater
import com.footagevault.app.net.YouTube
import com.footagevault.app.work.BackupWorker
import com.footagevault.app.work.ImportService
import com.footagevault.app.work.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val db = Db.get(ctx)
    val prefs = Prefs(ctx)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items = _items.asStateFlow()
    private val _albums = MutableStateFlow<List<Db.Album>>(emptyList())
    val albums = _albums.asStateFlow()
    private val _events = MutableStateFlow<List<VaultEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _duplicates = MutableStateFlow<List<List<Item>>>(emptyList())
    val duplicates = _duplicates.asStateFlow()
    private val _signedIn = MutableStateFlow(prefs.signedIn)
    val signedIn = _signedIn.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    /** Screens that need the system UI (Google consent, delete confirmation) */
    data class SysIntent(val kind: String, val pi: PendingIntent)
    private val _intents = MutableSharedFlow<SysIntent>(extraBufferCapacity = 2)
    val intents = _intents.asSharedFlow()
    private var pendingDelete: List<Item> = emptyList()

    private val yt = YouTube { Auth.silentToken(ctx) ?: throw ApiException(401, "Sign in to YouTube first") }
    private val sync = Sync(ctx, yt)

    init {
        viewModelScope.launch(Dispatchers.IO) { Telegram.start(ctx) }
        @OptIn(FlowPreview::class)
        viewModelScope.launch { db.version.debounce(150).collect { reload() } }
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        val all = db.items()
        _items.value = all
        _albums.value = db.albums()
        _events.value = Events.build(all)
        _duplicates.value = findDuplicates(all)
    }

    fun say(msg: String) { _messages.tryEmit(msg) }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: ApiException) {
            say(if (e.isAuth) "Sign in to YouTube first" else if (e.isQuota) "YouTube's daily limit is reached. Try again tomorrow." else "YouTube: ${e.code}")
        } catch (e: Exception) { say(e.message ?: "Something went wrong") }
    }

    // ---------- sign-in ----------
    fun signIn() = io {
        when (val r = Auth.authorize(ctx)) {
            is Auth.Result.Token -> onSignedIn()
            is Auth.Result.NeedsUser -> _intents.tryEmit(SysIntent("auth", r.intent))
            is Auth.Result.Error -> say("Google sign-in failed: ${r.message}")
        }
    }

    fun onSignedIn() = io {
        prefs.signedIn = true
        _signedIn.value = true
        _busy.value = true
        try { sync.pull() } finally { _busy.value = false }
        BackupWorker.runNow(ctx)
    }

    fun signOut() { prefs.signedIn = false; _signedIn.value = false }

    fun refresh() = io {
        MediaScanner.scan(ctx)
        if (prefs.signedIn) { _busy.value = true; try { sync.pull() } finally { _busy.value = false } }
        BackupWorker.runNow(ctx)
    }

    /** Why videos haven't uploaded yet, in plain words (Uploading page). */
    fun uploadState(items: List<Item>): Pair<String, String> {
        val pending = items.filter { it.isPending }
        val up = pending.firstOrNull { it.status == "uploading" }
        val waiting = pending.count { it.status != "processing" }
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val charging = ctx.getSystemService(android.os.BatteryManager::class.java).isCharging
        val err = prefs.lastError
        val tgWaiting = pending.any { it.onTelegram && it.status == "queued" }
        val tgReady = (Telegram.state.value as? Telegram.State.Ready)?.channel == true
        val resume = java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a")
            .format(java.time.Instant.ofEpochMilli(prefs.pausedUntil).atZone(java.time.ZoneId.systemDefault()))
        return when {
            tgWaiting && !tgReady && pending.none { !it.onTelegram } -> "Waiting for sign-in" to
                "Open Settings → Telegram and sign in to back these up."
            !prefs.signedIn && pending.any { !it.onTelegram } -> "Not signed in" to "Sign in to YouTube (Settings) to start uploading."
            prefs.userPaused -> "Backup is paused" to "Turn off Pause backup in Settings."
            System.currentTimeMillis() < prefs.pausedUntil -> "Daily upload limit reached" to
                "Long videos continue by themselves after $resume."
            ImportService.progress.value != null -> ImportService.progress.value!!.let { c ->
                "Copying from Google Photos" to "${c.done} of ${c.total} copied (${BackupWorker.fmtSize(c.bytes)} of ${BackupWorker.fmtSize(c.totalBytes)}). " +
                    "Uploads start when all are copied. You can close the app."
            }
            up != null -> "Backing up ${up.name}" to "${(up.progress * 100).toInt()}% · $waiting left"
            waiting == 0 -> "Finishing up" to "${pending.size} uploaded and being finished. Long videos can take a while."
            !online -> "No internet" to "Uploads start when the phone is online."
            prefs.wifiOnly && !unmetered -> "Waiting for Wi-Fi" to "Only on Wi-Fi is on in Settings, and the phone is on mobile data."
            prefs.chargingOnly && !charging -> "Waiting for charging" to "Only while charging is on in Settings. Plug the phone in."
            err != null -> "The last try didn't work" to "$err\nIt tries again by itself. Tap Back up now to retry."
            pending.any { it.error != null } -> "The last upload didn't work" to
                "${pending.first { it.error != null }.error}\nIt tries again by itself. Tap Back up now to retry."
            else -> "Starting soon" to "$waiting waiting. Android starts the backup within a few minutes."
        }
    }

    fun rescan() = io { MediaScanner.scan(ctx); BackupWorker.runNow(ctx) }
    fun backUpNow() { BackupWorker.runNow(ctx); say("Backing up…") }

    // ---------- favourites ----------
    fun setFavorite(ids: Collection<Long>, on: Boolean) = io {
        for (id in ids) db.update(id, "favorite" to on, "fav_synced" to false)
        if (prefs.signedIn) sync.pushFavourites()
    }

    // ---------- change date ----------
    /**
     * Moves videos to [date]. With [time] = null each keeps its own time of day (so their order stays);
     * otherwise they all get that time, a second apart, in their current order.
     */
    fun setDate(list: List<Item>, date: java.time.LocalDate, time: java.time.LocalTime?) = io {
        val zone = java.time.ZoneId.systemDefault()
        list.sortedBy { it.recorded }.forEachIndexed { i, it ->
            val t = time?.plusSeconds(i.toLong()) ?: java.time.Instant.ofEpochMilli(it.recorded).atZone(zone).toLocalTime()
            val ms = date.atTime(t).atZone(zone).toInstant().toEpochMilli()
            db.update(it.id, "recorded" to ms, "manual_date" to true, "date_synced" to (it.ytId == null))
        }
        say(if (list.size == 1) "Date changed" else "Moved ${list.size} videos")
        if (prefs.signedIn) try { sync.pushDates() } catch (_: Exception) { BackupWorker.runNow(ctx) }  // retried by the next backup run
    }

    // ---------- albums ----------
    fun createAlbum(name: String, items: List<Item> = emptyList()) = io {
        val pid = yt.createPlaylist(name)
        db.addAlbum(pid, name)
        addTo(pid, items)
        say("Album created")
    }

    fun addToAlbum(pid: String, items: List<Item>) = io {
        addTo(pid, items)
        val name = albums.value.firstOrNull { it.playlistId == pid }?.name
        say("Added to ${name ?: "album"}")
    }

    private suspend fun addTo(pid: String, items: List<Item>) {
        val ready = items.filter { it.ytId != null }
        if (ready.size < items.size) say("${items.size - ready.size} not backed up yet. Add them once they are.")
        val have = db.albums().firstOrNull { it.playlistId == pid }?.ytIds.orEmpty().toSet()
        for (it in ready) if (it.ytId !in have) db.addToAlbum(pid, it.ytId!!, yt.addToPlaylist(pid, it.ytId))
    }

    fun removeFromAlbum(pid: String, items: List<Item>) = io {
        for (it in items) {
            val ytId = it.ytId ?: continue
            db.albumItemId(pid, ytId)?.let { itemId -> yt.removeFromPlaylist(itemId) }
            db.removeFromAlbum(pid, ytId)
        }
    }

    // ---------- share an album as a YouTube playlist link ----------
    /** What sharing would involve: null = ready (already unlisted/public). */
    data class ShareCheck(val pid: String, val name: String, val playlistPrivate: Boolean, val privateVideos: Int)
    private val _shareCheck = MutableStateFlow<ShareCheck?>(null)
    val shareCheck = _shareCheck.asStateFlow()
    fun dismissShare() { _shareCheck.value = null }

    fun shareAlbum(pid: String) = io {
        val (title, privacy) = yt.playlistInfo(pid) ?: return@io say("Album not found on YouTube")
        val ids = db.albums().firstOrNull { it.playlistId == pid }?.ytIds.orEmpty().toSet()
        val privateVideos = items.value.count { it.ytId in ids && it.privacy == "private" }
        if (privacy == "private" || privateVideos > 0) _shareCheck.value = ShareCheck(pid, title, privacy == "private", privateVideos)
        else sendAlbumLink(pid, title)
    }

    /** After the user agreed: make the playlist unlisted if needed, then open the share sheet. */
    fun confirmShareAlbum() = io {
        val c = _shareCheck.value ?: return@io
        _shareCheck.value = null
        if (c.playlistPrivate) yt.setPlaylistPrivacy(c.pid, c.name, "unlisted")
        sendAlbumLink(c.pid, c.name)
    }

    /**
     * Several videos as one link: a new unlisted YouTube playlist of them, in the order they were shot. Others can only
     * watch unlisted videos, so private ones in it are made unlisted first. Items not on YouTube are left out.
     */
    fun sharePlaylist(list: List<Item>) = io {
        val vids = list.filter { it.ytId != null }.sortedBy { it.recorded }
        if (vids.isEmpty()) return@io say("None of these are on YouTube yet")
        _busy.value = true
        var refused = 0
        try {
            val day = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault())
                .format(java.time.Instant.ofEpochMilli(vids.first().recorded).atZone(java.time.ZoneId.systemDefault()))
            val title = "Shared · $day"
            for (it in vids.filter { it.privacy == "private" }) {
                val got = try { yt.setPrivacy(it.ytId!!, "unlisted") } catch (e: ApiException) { if (e.isQuota) throw e; null }
                if (got == "unlisted") db.update(it.id, "privacy" to "unlisted") else refused++
            }
            val pid = yt.createPlaylist(title, "unlisted")
            db.addAlbum(pid, title)
            for (it in vids) db.addToAlbum(pid, it.ytId!!, yt.addToPlaylist(pid, it.ytId))
            sendAlbumLink(pid, title)
        } finally { _busy.value = false }
        val left = list.size - vids.size
        when {
            refused > 0 -> { prefs.unlistRefused = true; _unlistHelp.value = true }
            left > 0 -> say("$left not on YouTube yet ${if (left == 1) "was" else "were"} left out")
        }
    }

    private fun sendAlbumLink(pid: String, title: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            .putExtra(android.content.Intent.EXTRA_TEXT, "$title\nhttps://www.youtube.com/playlist?list=$pid")
        ctx.startActivity(android.content.Intent.createChooser(send, "Share album").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun renameAlbum(pid: String, name: String) = io { yt.renamePlaylist(pid, name); db.addAlbum(pid, name) }
    fun deleteAlbum(pid: String) = io { yt.deletePlaylist(pid); db.removeAlbum(pid); say("Album deleted") }

    // ---------- unlisted ----------
    /** YouTube kept videos private: show how to do it in YouTube Studio instead. */
    private val _unlistHelp = MutableStateFlow(false)
    val unlistHelp = _unlistHelp.asStateFlow()
    fun dismissUnlistHelp() { _unlistHelp.value = false }

    fun privateVideos() = items.value.filter { it.ytId != null && it.privacy == "private" }

    /**
     * Makes these YouTube videos unlisted, so they play inside the app (about 50 of YouTube's 10,000 daily units each).
     * Stops early if YouTube refuses, and explains the Studio route.
     */
    fun makeUnlisted(list: List<Item>) = io {
        val todo = list.filter { it.ytId != null && it.privacy == "private" }
        if (todo.isEmpty()) return@io say("Nothing private to change")
        _busy.value = true
        var done = 0; var refused = 0
        try {
            for (it in todo) {
                val got = try { yt.setPrivacy(it.ytId!!, "unlisted") } catch (e: ApiException) {
                    if (e.isQuota) { say(if (done > 0) "$done made unlisted. YouTube's daily limit is reached; do the rest tomorrow." else "YouTube's daily limit is reached. Try tomorrow."); return@io }
                    if (e.isNotFound) continue
                    if (e.code == 403 || e.code == 400) null else throw e
                }
                if (got == "unlisted") { db.update(it.id, "privacy" to "unlisted"); done++ } else refused++
                if (refused >= 2 && done == 0) break  // YouTube is refusing: don't spend the daily limit on the rest
            }
        } finally { _busy.value = false }
        if (refused > 0) { prefs.unlistRefused = true; _unlistHelp.value = true }
        else { prefs.unlistRefused = false; say(if (done == 1) "Now unlisted. It plays inside the app." else "$done now unlisted. They play inside the app.") }
    }

    // ---------- share ----------
    /**
     * Shares the files themselves: photos as images, videos as video files. Ones no longer on the phone are fetched
     * from Telegram first; a video that only lives on YouTube can only be shared as its link.
     */
    fun share(list: List<Item>) = io {
        val fetch = list.filter { !(it.onPhone && it.uri != null) && it.tgMsg != null }
        if (fetch.isNotEmpty()) say("Getting ${com.footagevault.app.data.countWords(fetch)} ready…")
        val auth = "${ctx.packageName}.files"
        val files = mutableListOf<Uri>()
        val links = mutableListOf<String>()
        for (it in list) when {
            it.onPhone && it.uri != null -> files += if (it.isImport)
                androidx.core.content.FileProvider.getUriForFile(ctx, auth, java.io.File(Uri.parse(it.uri).path!!)) else Uri.parse(it.uri)
            it.tgMsg != null -> files += androidx.core.content.FileProvider.getUriForFile(ctx, auth, java.io.File(Telegram.download(ctx, it) {}))
            it.ytId != null -> links += "https://youtu.be/${it.ytId}"
        }
        if (files.isEmpty() && links.isEmpty()) return@io say("Nothing to share yet")
        val shared = list.filter { it.onPhone || it.tgMsg != null }
        val type = when {
            shared.isNotEmpty() && shared.all { it.isPhoto } -> "image/*"
            shared.isNotEmpty() && shared.none { it.isPhoto } -> "video/*"
            files.isEmpty() -> "text/plain"
            else -> "*/*"
        }
        val send = android.content.Intent(if (files.size > 1) android.content.Intent.ACTION_SEND_MULTIPLE else android.content.Intent.ACTION_SEND).setType(type)
        when (files.size) {
            0 -> {}
            1 -> send.putExtra(android.content.Intent.EXTRA_STREAM, files[0])
            else -> send.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(files))
        }
        if (links.isNotEmpty()) send.putExtra(android.content.Intent.EXTRA_TEXT, links.joinToString("\n"))
        if (files.isNotEmpty()) {
            send.clipData = android.content.ClipData.newRawUri("", files[0]).apply { files.drop(1).forEach { u -> addItem(android.content.ClipData.Item(u)) } }
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            ctx.startActivity(android.content.Intent.createChooser(send, "Share")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    // ---------- free up space ----------
    fun freeCandidates() = items.value.filter { it.onPhone && it.status == "backed_up" && it.uri != null }

    /** Re-checks each video on YouTube, then asks Android to delete them (one system prompt). */
    fun freeUpSpace(list: List<Item> = freeCandidates()) = io {
        if (list.isEmpty()) return@io say("Nothing to free up")
        _busy.value = true
        // Each copy is checked where it lives: YouTube (processed, same length) or Telegram (post holds the whole file).
        val (onTg, onYt) = list.partition { it.onTelegram }
        val safe = try {
            (if (onYt.isEmpty()) emptyList() else sync.verifySafe(onYt)) + onTg.filter { Telegram.verify(ctx, it) }
        } finally { _busy.value = false }
        if (safe.isEmpty()) return@io say("Couldn't confirm the backups yet. Try again later.")
        if (safe.size < list.size) say("${list.size - safe.size} kept: the backup copy didn't check out")
        requestPhoneDelete(safe)
    }

    /**
     * Gallery videos go through Android's delete prompt. Copies shared in from Google Photos are the app's own
     * files, so they go right away.
     */
    private fun requestPhoneDelete(list: List<Item>) {
        val (copies, gallery) = list.partition { it.isImport }
        for (it in copies) { com.footagevault.app.data.Importer.deleteCopy(it); forgetPhoneCopy(it) }
        if (gallery.isEmpty()) { if (copies.isNotEmpty()) say("Deleted from the phone"); return }
        pendingDelete = gallery
        _intents.tryEmit(SysIntent("delete", MediaStore.createDeleteRequest(ctx.contentResolver, gallery.map { Uri.parse(it.uri) })))
    }

    private fun forgetPhoneCopy(it: Item) {
        if (it.ytId != null || it.tgMsg != null) db.update(it.id, "on_phone" to false, "media_id" to null, "uri" to null)
        else db.delete(it.id)  // a phone-only video: nothing left of it
    }

    /** After the system delete prompt. */
    fun onDeleteResult(ok: Boolean) = io {
        if (!ok) { pendingDelete = emptyList(); return@io }
        val bytes = pendingDelete.sumOf { it.size }
        for (it in pendingDelete) forgetPhoneCopy(it)
        say(if (pendingDelete.all { it.ytId != null || it.tgMsg != null }) "Freed ${BackupWorker.fmtSize(bytes)}. They're still in your backup." else "Deleted from the phone")
        pendingDelete = emptyList()
    }

    /** Removes these from the phone without the YouTube check (used for duplicate copies). */
    fun removeFromPhone(list: List<Item>) = io {
        // Uploading / still processing on YouTube: the phone copy may be the only good one yet.
        val busy = list.filter { it.onPhone && it.ytId != null && it.status != "backed_up" }
        if (busy.isNotEmpty()) say("${busy.size} still processing on YouTube; kept on the phone for now")
        val onPhone = list.filter { it.onPhone && it.uri != null && it !in busy }
        if (onPhone.isEmpty()) return@io
        requestPhoneDelete(onPhone)
    }

    /** Permanently deletes the YouTube copy (duplicates). A phone copy stays, marked "don't back up". */
    fun deleteFromYouTube(item: Item) = io {
        val id = item.ytId ?: return@io
        yt.deleteVideo(id)
        forgetYouTubeCopy(item)
        say("Deleted from YouTube")
    }

    /**
     * After the YouTube copy is gone: a phone copy becomes "excluded" (never re-uploaded by itself);
     * a YouTube-only entry disappears.
     */
    private fun forgetYouTubeCopy(item: Item) {
        if (item.onTelegram && item.tgMsg != null) return  // still on Telegram
        if (item.onPhone) db.update(item.id, "yt_id" to null, "status" to "excluded", "privacy" to null, "upload_url" to null)
        else db.delete(item.id)
    }

    /** "Delete everywhere": from YouTube (permanent), then Android's own prompt for any phone copies. */
    fun deleteEverywhere(list: List<Item>) = io {
        var gone = 0
        for (it in list) {
            val id = it.ytId
            if (id != null) {
                try { yt.deleteVideo(id) } catch (e: ApiException) { if (!e.isNotFound) throw e }  // already gone is fine
                gone++
            }
            it.tgMsg?.let { msg -> Telegram.delete(ctx, msg); db.update(it.id, "tg_msg" to null); gone++ }
            forgetYouTubeCopy(db.item(it.id) ?: it)
        }
        val onPhone = list.filter { it.onPhone && it.uri != null }
        if (onPhone.isNotEmpty()) {
            // Fresh rows: they no longer have a YouTube id, so the phone delete removes them completely.
            requestPhoneDelete(onPhone.mapNotNull { db.item(it.id) })
        }
        if (onPhone.isNotEmpty()) say("Confirm to remove the phone copies too")
        else if (gone > 0) say(if (gone == 1) "Deleted" else "Deleted $gone")
    }

    // ---------- duplicates ----------

    /**
     * Copies of the same video: same length (±1 s) and the same file name, recording time (±3 s) or size.
     * Catches the same clip uploaded twice to YouTube, or saved twice on the phone.
     */
    private fun findDuplicates(all: List<Item>): List<List<Item>> {
        fun stem(n: String) = n.substringBeforeLast('.').lowercase()
            .replace(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2} · """), "")  // vault upload titles
            .replace(Regex("""\s*\(\d+\)$"""), "").trim()
        val list = all.filter { it.durationMs > 0 }.sortedBy { it.durationMs }
        val parent = IntArray(list.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in list.indices) {
            var j = i + 1
            while (j < list.size && list[j].durationMs - list[i].durationMs <= 1000) {
                val a = list[i]; val b = list[j]
                val same = stem(a.name) == stem(b.name) || kotlin.math.abs(a.recorded - b.recorded) <= 3000 || (a.size > 0 && a.size == b.size)
                if (same) parent[find(i)] = find(j)
                j++
            }
        }
        return list.indices.groupBy(::find).values.filter { it.size > 1 }.map { g -> g.map { list[it] }.sortedByDescending { it.recorded } }
            .sortedByDescending { it.first().recorded }
    }

    // ---------- look ----------
    private val _theme = MutableStateFlow(prefs.theme)
    val theme = _theme.asStateFlow()
    fun setTheme(mode: String) { prefs.theme = mode; _theme.value = mode }
    private val _columns = MutableStateFlow(prefs.gridColumns)
    val columns = _columns.asStateFlow()
    fun setColumns(n: Int) { prefs.gridColumns = n; _columns.value = n }

    fun retry(item: Item) = io { db.update(item.id, "status" to "queued", "attempts" to 0, "error" to null); BackupWorker.runNow(ctx) }

    // ---------- app updates (GitHub Releases) ----------
    private val _update = MutableStateFlow<Updater.Update?>(null)
    val update = _update.asStateFlow()
    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress = _updateProgress.asStateFlow()

    fun checkForUpdate(manual: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        val u = try { Updater.check(ctx) } catch (e: Exception) { if (manual) say("Couldn't check for updates"); return@launch }
        _update.value = u
        if (manual && u == null) say("You have the latest version")
    }

    fun installUpdate() = viewModelScope.launch(Dispatchers.IO) {
        val u = _update.value ?: return@launch
        if (!Updater.canInstall(ctx)) {
            say("Allow Footage Vault to install updates, then tap Update again")
            Updater.openInstallPermission(ctx)
            return@launch
        }
        try {
            _updateProgress.value = 0f
            val apk = Updater.download(ctx, u) { _updateProgress.value = it }
            Updater.install(ctx, apk)
        } catch (e: Exception) {
            say("Update download failed: ${e.message}")
        } finally {
            _updateProgress.value = null
        }
    }

    // ---------- settings ----------
    fun settingsChanged() { BackupWorker.schedule(ctx, replace = true); rescan() }

    // ---------- Telegram (photos and short videos) ----------
    val telegram = Telegram.state
    private val _tgBusy = MutableStateFlow(false)
    val tgBusy = _tgBusy.asStateFlow()
    private fun tg(block: suspend () -> String?) = viewModelScope.launch(Dispatchers.IO) {
        _tgBusy.value = true
        try { block()?.let(::say) } catch (e: Exception) { say(e.message ?: "Telegram didn't respond") } finally { _tgBusy.value = false }
    }
    fun tgKeys(apiId: String, apiHash: String) = tg {
        val id = apiId.trim().toIntOrNull() ?: return@tg "The API ID is a number"
        if (apiHash.trim().length < 20) return@tg "The API hash is the long code under it"
        Telegram.setKeys(ctx, id, apiHash); null
    }
    fun tgPhone(phone: String) = tg { Telegram.sendPhone(ctx, phone) }
    fun tgCode(code: String) = tg { Telegram.sendCode(ctx, code) }
    fun tgPassword(pw: String) = tg { Telegram.sendPassword(ctx, pw) }
    fun tgDisconnect() = tg { Telegram.disconnect(ctx); Router.reroute(db, prefs); "Telegram disconnected. Its channel and files stay in Telegram." }
    /** Photo access was just allowed, or the channel became ready: pick up the photos. */
    fun tgChanged() = io { Router.reroute(db, prefs); MediaScanner.scan(ctx); BackupWorker.runNow(ctx) }
    fun setShortMinutes(min: Int) { prefs.tgShortMinutes = min; tgChanged() }
}
