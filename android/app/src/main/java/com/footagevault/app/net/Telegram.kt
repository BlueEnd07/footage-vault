package com.footagevault.app.net

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.footagevault.app.data.Item
import com.footagevault.app.data.Prefs
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatMemberStatusCreator
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputThumbnail
import dev.g000sha256.tdl.dto.MessageDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TelegramException(val code: Int, message: String) : Exception(message) {
    /** Telegram asks to slow down ("Too Many Requests: retry after N"): try again later, not a failed file. */
    val isFlood get() = code == 429
}

/**
 * Photos and short videos go to a private Telegram channel in the user's own account, as the original files.
 * Uses TDLib (Telegram's own client library) with the user's API ID / hash from my.telegram.org.
 *
 * Each upload is a file ("document", so Telegram keeps it byte for byte) whose caption carries the same
 * details as a YouTube upload: `Original file: NAME` and `fv:{…}`.
 */
object Telegram {
    const val CHANNEL_TITLE = "Footage Vault"
    private const val CHANNEL_ABOUT = "Photos and short videos backed up by Footage Vault. Keep this channel private."

    sealed interface State {
        /** No API ID / hash yet. */
        data object NotSetUp : State
        data object Starting : State
        data object WaitPhone : State
        data class WaitCode(val sentTo: String) : State
        data class WaitPassword(val hint: String) : State
        /** Signed in; [name] is the account, [channel] = the backup channel is ready. */
        data class Ready(val name: String, val channel: Boolean) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotSetUp)
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val channelLock = Mutex()
    @Volatile private var client: TdlClient? = null

    private fun <T> TdlResult<T>.get(): T = when (this) {
        is TdlResult.Success -> result
        is TdlResult.Failure -> throw TelegramException(code, message)
    }

    /** Starts TDLib if the keys are set (safe to call often). */
    suspend fun start(ctx: Context): TdlClient? = lock.withLock {
        client?.let { return it }
        val prefs = Prefs(ctx)
        if (prefs.tgApiId == 0 || prefs.tgApiHash.isNullOrBlank()) { _state.value = State.NotSetUp; return null }
        _state.value = State.Starting
        val c = TdlClient.create()
        client = c
        val app = ctx.applicationContext
        // Updates aren't replayed: listen first (subscribes right away), then wake TDLib with a first request.
        // Every sign-in step then arrives as an update, once.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onState(app, c, it.authorizationState) }
        }
        scope.launch { c.getAuthorizationState() }
        c
    }

    private suspend fun onState(ctx: Context, c: TdlClient, s: AuthorizationState) {
        val prefs = Prefs(ctx)
        when (s) {
            is AuthorizationStateWaitTdlibParameters -> {
                val r = c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = File(ctx.filesDir, "tdlib").path,
                    filesDirectory = File(ctx.cacheDir, "tdlib").path,
                    databaseEncryptionKey = ByteArray(0),
                    useFileDatabase = true,
                    useChatInfoDatabase = true,
                    // No message database: a send interrupted by Android isn't silently re-sent later (no doubles).
                    useMessageDatabase = false,
                    useSecretChats = false,
                    apiId = prefs.tgApiId,
                    apiHash = prefs.tgApiHash.orEmpty(),
                    systemLanguageCode = Locale.getDefault().language.ifBlank { "en" },
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    applicationVersion = "Footage Vault",
                )
                if (r is TdlResult.Failure) _state.value = State.Error(friendly(r.message))
            }
            is AuthorizationStateWaitPhoneNumber -> _state.value = State.WaitPhone
            is AuthorizationStateWaitCode -> _state.value = State.WaitCode(
                if (s.codeInfo.type is dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage) "your Telegram app" else s.codeInfo.phoneNumber)
            is AuthorizationStateWaitPassword -> _state.value = State.WaitPassword(s.passwordHint)
            is AuthorizationStateReady -> {
                val me = (c.getMe() as? TdlResult.Success)?.result
                val name = listOfNotNull(me?.firstName, me?.lastName).joinToString(" ").trim().ifBlank { "Telegram" }
                _state.value = State.Ready(name, prefs.tgChatId != 0L)
                scope.launch { runCatching { ensureChannel(ctx) }.onFailure { _state.value = State.Error(friendly(it.message)) } }
            }
            is AuthorizationStateClosed -> {
                if (client === c) client = null
                if (_state.value !is State.Error) _state.value = if (prefs.tgApiId == 0) State.NotSetUp else State.Starting
            }
            is dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut, is dev.g000sha256.tdl.dto.AuthorizationStateClosing -> {}
            else -> _state.value = State.Error("Telegram asked for a step this app doesn't support (${s::class.simpleName}). Sign in once in the Telegram app, then try again.")
        }
    }

    private fun friendly(msg: String?) = when {
        msg == null -> "Telegram didn't respond"
        "API_ID" in msg -> "The API ID or hash isn't right. Copy them again from my.telegram.org."
        "PHONE_NUMBER_INVALID" in msg -> "That phone number isn't valid. Include the country code, like +91…"
        "PHONE_CODE_INVALID" in msg -> "That code isn't right."
        "PHONE_CODE_EXPIRED" in msg -> "That code has expired. Start again."
        "PASSWORD_HASH_INVALID" in msg -> "That password isn't right."
        else -> msg
    }

    /** Sign-in steps. Each returns an error to show, or null. */
    suspend fun sendPhone(ctx: Context, phone: String) = step(ctx) { it.setAuthenticationPhoneNumber(phone.replace(" ", "")).get() }
    suspend fun sendCode(ctx: Context, code: String) = step(ctx) { it.checkAuthenticationCode(code.trim()).get() }
    suspend fun sendPassword(ctx: Context, pw: String) = step(ctx) { it.checkAuthenticationPassword(pw).get() }

    private suspend fun step(ctx: Context, block: suspend (TdlClient) -> Unit): String? = try {
        block(start(ctx) ?: return "Enter your API ID and hash first")
        null
    } catch (e: TelegramException) { friendly(e.message) }

    /** Saves the keys and starts signing in. */
    suspend fun setKeys(ctx: Context, apiId: Int, apiHash: String) {
        val prefs = Prefs(ctx)
        prefs.tgApiId = apiId; prefs.tgApiHash = apiHash.trim()
        start(ctx)
    }

    /** Signs out of Telegram on this phone and forgets the keys. The channel and its files stay in Telegram. */
    suspend fun disconnect(ctx: Context) {
        val c = lock.withLock { client }
        runCatching { c?.logOut() }
        val prefs = Prefs(ctx)
        prefs.tgApiId = 0; prefs.tgApiHash = null; prefs.tgChatId = 0L
        lock.withLock { client = null }
        _state.value = State.NotSetUp
    }

    /** Signed in and the channel is set up (waits a little for TDLib to start). */
    suspend fun ready(ctx: Context): Boolean {
        start(ctx) ?: return false
        val s = withTimeoutOrNull(30_000) { state.first { it is State.Ready && it.channel || it is State.Error || it is State.WaitPhone || it is State.WaitCode || it is State.WaitPassword } }
        return s is State.Ready && s.channel
    }

    /** Finds the private "Footage Vault" channel this account made (another phone may have made it), or makes one. */
    private suspend fun ensureChannel(ctx: Context): Unit = channelLock.withLock {  // never two channels
        val c = start(ctx) ?: return
        val prefs = Prefs(ctx)
        if (prefs.tgChatId != 0L && c.getChat(prefs.tgChatId) is TdlResult.Success) { markChannel(true); return }
        repeat(10) { if (c.loadChats(ChatListMain(), 100) is TdlResult.Failure) return@repeat }  // 404 = all loaded
        val ids = c.getChats(ChatListMain(), 1000).get().chatIds
        val found = ids.firstOrNull { id ->
            val chat = (c.getChat(id) as? TdlResult.Success)?.result ?: return@firstOrNull false
            val type = chat.type as? ChatTypeSupergroup ?: return@firstOrNull false
            type.isChannel && chat.title == CHANNEL_TITLE &&
                (c.getSupergroup(type.supergroupId) as? TdlResult.Success)?.result?.status is ChatMemberStatusCreator
        }
        prefs.tgChatId = found ?: c.createNewSupergroupChat(CHANNEL_TITLE, isForum = false, isChannel = true, description = CHANNEL_ABOUT,
            messageAutoDeleteTime = 0, forImport = false).get().id
        markChannel(true)
    }

    private fun markChannel(ok: Boolean) {
        (state.value as? State.Ready)?.let { _state.value = it.copy(channel = ok) }
    }

    /** A finished upload: the message in the channel, and the preview kept for the gallery. */
    class Sent(val messageId: Long, val thumb: String?)

    /**
     * Uploads one phone item as an original file.
     * The preview made for Telegram is also kept (files/thumbs), so the gallery still shows it after the phone copy is gone.
     * If Android stops the job mid-way, the half-sent message is deleted so the retry doesn't make a second copy.
     */
    suspend fun upload(ctx: Context, it: Item, onProgress: (Long) -> Unit): Sent = coroutineScope {
        val c = start(ctx) ?: throw TelegramException(401, "Telegram isn't connected")
        val chatId = Prefs(ctx).tgChatId
        if (chatId == 0L) throw TelegramException(400, "The Telegram channel isn't set up yet")
        val (path, temp) = localFile(ctx, it)
        val thumb = thumbnail(ctx, it)
        val done = CompletableDeferred<Result<Long>>()
        var tempId = 0L
        var fileId = -1
        val results = HashMap<Long, Result<Long>>()  // updates may race the send call
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                c.messageSendSucceededUpdates.collect { u ->
                    synchronized(results) { results[u.oldMessageId] = Result.success(u.message.id); if (u.oldMessageId == tempId) done.complete(results[tempId]!!) }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                c.messageSendFailedUpdates.collect { u ->
                    val r = Result.failure<Long>(TelegramException(u.error.code, u.error.message))
                    synchronized(results) { results[u.oldMessageId] = r; if (u.oldMessageId == tempId) done.complete(r) }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                c.fileUpdates.collect { u -> if (u.file.id == fileId) onProgress(u.file.remote.uploadedSize) }
            }
        }
        try {
            val content = InputMessageDocument(
                InputDocument(InputFileLocal(path), thumb?.let { (p, w, h) -> InputThumbnail(InputFileLocal(p), w, h) }, disableContentTypeDetection = true),
                FormattedText(caption(it), emptyArray()),
            )
            val msg = c.sendMessage(chatId = chatId, inputMessageContent = content).get()
            fileId = (msg.content as? MessageDocument)?.document?.document?.id ?: -1
            synchronized(results) { tempId = msg.id; results[msg.id]?.let { r -> done.complete(r) } }
            Sent(done.await().getOrThrow(), thumb?.first)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (tempId != 0L) withContext(NonCancellable) { runCatching { c.deleteMessages(chatId, longArrayOf(tempId), revoke = true) } }
            throw e
        } finally {
            watch.cancel()
            if (temp) File(path).delete()
        }
    }

    /** The file in a backed-up post (its id is only valid in this TDLib session, so it's looked up each time). */
    private suspend fun fileOf(ctx: Context, it: Item): dev.g000sha256.tdl.dto.File {
        val c = start(ctx) ?: throw TelegramException(401, "Telegram isn't connected")
        if (!ready(ctx)) throw TelegramException(401, "Telegram isn't connected. Open Settings → Telegram.")
        val msg = c.getMessage(Prefs(ctx).tgChatId, it.tgMsg ?: throw TelegramException(404, "Not on Telegram")).get()
        return (msg.content as? MessageDocument)?.document?.document ?: throw TelegramException(404, "The Telegram post has no file")
    }

    /** Before "Free up space": the post is still there and holds the whole file. */
    suspend fun verify(ctx: Context, it: Item): Boolean = try {
        val f = fileOf(ctx, it)
        f.remote.isUploadingCompleted && (f.size == it.size || f.expectedSize == it.size)
    } catch (_: TelegramException) { false }

    /**
     * Downloads the original from the channel into TDLib's cache (once) and returns its path, for viewing items that
     * are no longer on the phone.
     */
    private val copies = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** An original already fetched this session (no Telegram round trip), or null. */
    fun cachedCopy(itemId: Long): String? = copies[itemId]?.takeIf { File(it).exists() }

    suspend fun download(ctx: Context, it: Item, onProgress: (Float) -> Unit): String =
        (cachedCopy(it.id) ?: fetch(ctx, it, onProgress)).also { path -> copies[it.id] = path }

    private suspend fun fetch(ctx: Context, it: Item, onProgress: (Float) -> Unit): String = coroutineScope {
        val c = start(ctx) ?: throw TelegramException(401, "Telegram isn't connected")
        val f = fileOf(ctx, it)
        if (f.local.isDownloadingCompleted && File(f.local.path).exists()) return@coroutineScope f.local.path
        // TDLib may still point at the phone file it uploaded from, which is now deleted: forget that copy first.
        if (f.local.isDownloadingCompleted) runCatching { c.deleteFile(f.id) }
        val total = if (f.size > 0) f.size else f.expectedSize
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            c.fileUpdates.collect { u -> if (u.file.id == f.id && total > 0) onProgress(u.file.local.downloadedSize.toFloat() / total) }
        }
        try {
            val done = c.downloadFile(f.id, priority = 32, offset = 0, limit = 0, synchronous = true).get()
            if (!done.local.isDownloadingCompleted) throw TelegramException(500, "The download didn't finish")
            done.local.path
        } finally { watch.cancel() }
    }

    /** Opens the file in the Telegram app (t.me link to the channel post). */
    fun link(ctx: Context, messageId: Long): String {
        val channel = Prefs(ctx).tgChatId.toString().removePrefix("-100")
        return "https://t.me/c/$channel/${messageId shr 20}"  // TDLib message id = server id << 20
    }

    /** Deletes a backed-up file from the channel (Delete everywhere). */
    suspend fun delete(ctx: Context, messageId: Long) {
        val c = start(ctx) ?: throw TelegramException(401, "Telegram isn't connected")
        c.deleteMessages(Prefs(ctx).tgChatId, longArrayOf(messageId), revoke = true).get()
    }

    /** Same details as a YouTube upload description, so any device can rebuild the library from the channel. */
    private fun caption(it: Item): String {
        val fv = JSONObject().put("n", it.name).put("r", it.recorded).put("s", it.size).put("k", it.kind)
        if (it.durationMs > 0) fv.put("d", it.durationMs)
        if (it.width > 0) fv.put("w", it.width).put("h", it.height)
        if (it.lat != null && it.lon != null) fv.put("lat", it.lat).put("lon", it.lon)
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(it.recorded).atZone(ZoneId.systemDefault()))
        return "$day · ${it.name}\nOriginal file: ${it.name}\nfv:$fv"
    }

    /** TDLib reads files by path. Gallery files are readable by path with media access; otherwise a temporary copy. */
    private fun localFile(ctx: Context, it: Item): Pair<String, Boolean> {
        val uri = Uri.parse(it.uri ?: throw java.io.FileNotFoundException("The file is no longer on the phone"))
        if (uri.scheme == "file") return uri.path!! to false
        val path = runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (path != null && File(path).canRead()) return path to false
        val tmp = File(File(ctx.cacheDir, "tgup").apply { mkdirs() }, "${it.id}_${it.name}")
        (ctx.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("The file is no longer on the phone"))
            .use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        return tmp.path to true
    }

    /** A small JPEG preview (Telegram's limit is 320 px), so the channel shows thumbnails. */
    private fun thumbnail(ctx: Context, it: Item): Triple<String, Int, Int>? = runCatching {
        val uri = Uri.parse(it.uri!!)
        val bmp: Bitmap = if (uri.scheme == "file") {
            val f = File(uri.path!!)
            if (it.kind == "photo") android.media.ThumbnailUtils.createImageThumbnail(f, Size(320, 320), null)
            else android.media.ThumbnailUtils.createVideoThumbnail(f, Size(320, 320), null)
        } else ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null)
        val out = File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, "${it.id}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        Triple(out.path, bmp.width, bmp.height)
    }.getOrNull()
}
