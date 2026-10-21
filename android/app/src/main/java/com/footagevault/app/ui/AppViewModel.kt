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
        for (it in pendingDelete) db.update(it.id, "on_phone" to false, "media_id" to null, "uri" to null)
        say("Freed ${BackupWorker.fmtSize(bytes)}. They're still on YouTube.")
        pendingDelete = emptyList()
    }

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
