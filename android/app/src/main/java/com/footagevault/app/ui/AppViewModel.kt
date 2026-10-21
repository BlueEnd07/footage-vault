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
import com.footagevault.app.net.ApiException
import com.footagevault.app.net.Auth
import com.footagevault.app.net.Updater
import com.footagevault.app.net.YouTube
import com.footagevault.app.work.BackupWorker
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

    private fun sendAlbumLink(pid: String, title: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            .putExtra(android.content.Intent.EXTRA_TEXT, "$title\nhttps://www.youtube.com/playlist?list=$pid")
        ctx.startActivity(android.content.Intent.createChooser(send, "Share album").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun renameAlbum(pid: String, name: String) = io { yt.renamePlaylist(pid, name); db.addAlbum(pid, name) }
    fun deleteAlbum(pid: String) = io { yt.deletePlaylist(pid); db.removeAlbum(pid); say("Album deleted") }

    // ---------- free up space ----------
    fun freeCandidates() = items.value.filter { it.onPhone && it.status == "backed_up" && it.uri != null }

    /** Re-checks each video on YouTube, then asks Android to delete them (one system prompt). */
    fun freeUpSpace(list: List<Item> = freeCandidates()) = io {
        if (list.isEmpty()) return@io say("Nothing to free up")
        _busy.value = true
        val safe = try { sync.verifySafe(list) } finally { _busy.value = false }
        if (safe.isEmpty()) return@io say("Couldn't confirm these on YouTube yet. Try again later.")
        if (safe.size < list.size) say("${list.size - safe.size} kept: YouTube's copy didn't check out")
        pendingDelete = safe
        _intents.tryEmit(SysIntent("delete", MediaStore.createDeleteRequest(ctx.contentResolver, safe.map { Uri.parse(it.uri) })))
    }

    /** After the system delete prompt. */
    fun onDeleteResult(ok: Boolean) = io {
        if (!ok) { pendingDelete = emptyList(); return@io }
        val bytes = pendingDelete.sumOf { it.size }
        for (it in pendingDelete) {
            if (it.ytId != null) db.update(it.id, "on_phone" to false, "media_id" to null, "uri" to null)
            else db.delete(it.id)  // a phone-only duplicate: nothing left of it
        }
        say(if (pendingDelete.all { it.ytId != null }) "Freed ${BackupWorker.fmtSize(bytes)}. They're still on YouTube." else "Deleted from the phone")
        pendingDelete = emptyList()
    }

    /** Removes these from the phone without the YouTube check (used for duplicate copies). */
    fun removeFromPhone(list: List<Item>) = io {
        // Uploading / still processing on YouTube: the phone copy may be the only good one yet.
        val busy = list.filter { it.onPhone && it.ytId != null && it.status != "backed_up" }
        if (busy.isNotEmpty()) say("${busy.size} still processing on YouTube; kept on the phone for now")
        val onPhone = list.filter { it.onPhone && it.uri != null && it !in busy }
        if (onPhone.isEmpty()) return@io
        pendingDelete = onPhone
        _intents.tryEmit(SysIntent("delete", MediaStore.createDeleteRequest(ctx.contentResolver, onPhone.map { Uri.parse(it.uri) })))
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
            forgetYouTubeCopy(it)
        }
        val onPhone = list.filter { it.onPhone && it.uri != null }
        if (onPhone.isNotEmpty()) {
            // Fresh rows: they no longer have a YouTube id, so the phone delete removes them completely.
            pendingDelete = onPhone.mapNotNull { db.item(it.id) }
            _intents.tryEmit(SysIntent("delete", MediaStore.createDeleteRequest(ctx.contentResolver, onPhone.map { Uri.parse(it.uri) })))
        }
        if (gone > 0) say("Deleted $gone from YouTube" + if (onPhone.isNotEmpty()) "; confirm to delete from the phone too" else "")
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
}
