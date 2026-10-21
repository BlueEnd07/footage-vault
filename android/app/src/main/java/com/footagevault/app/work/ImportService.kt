package com.footagevault.app.work

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.footagevault.app.data.Db
import com.footagevault.app.data.Importer
import com.footagevault.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Copies videos shared in from Google Photos, in the background, a few at a time. Uploads wait until every
 * video is copied (the copies are "copied", not "queued", while this runs), so downloading from Google Photos
 * and uploading to YouTube don't fight over the connection.
 *
 * Share links are only lent to the screen that received them; passing them here with the read grant keeps
 * them usable while this service runs, so the import screen can be closed.
 */
class ImportService : Service() {

    data class Progress(val done: Int, val total: Int, val bytes: Long, val totalBytes: Long)
    private class Batch(val sources: List<Importer.Source>, val album: String?, val startId: Int)

    companion object {
        private const val PARALLEL = 3
        private val _progress = MutableStateFlow<Progress?>(null)
        /** Copy progress while an import runs, else null. */
        val progress = _progress.asStateFlow()
        val running get() = _progress.value != null

        fun start(ctx: Context, sources: List<Importer.Source>, album: String?) {
            if (sources.isEmpty()) return
            val clip = ClipData.newRawUri("videos", sources[0].uri)
            for (s in sources.drop(1)) clip.addItem(ClipData.Item(s.uri))
            val i = Intent(ctx, ImportService::class.java)
                .putParcelableArrayListExtra("uris", ArrayList(sources.map { it.uri }))
                .putExtra("names", sources.map { it.name }.toTypedArray())
                .putExtra("sizes", sources.map { it.size }.toLongArray())
                .putExtra("album", album?.trim()?.ifBlank { null })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = clip
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = ArrayDeque<Batch>()
    private var looping = false
    private var total = 0; private var done = 0; private var totalBytes = 0L
    private val copied = ConcurrentHashMap<Uri, Long>()
    private var added = 0; private var duplicates = 0; private var failed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, Notify.ID_IMPORT, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val uris = intent?.let { IntentCompat.getParcelableArrayListExtra(it, "uris", Uri::class.java) }.orEmpty()
        val names = intent?.getStringArrayExtra("names").orEmpty()
        val sizes = intent?.getLongArrayExtra("sizes") ?: LongArray(0)
        if (uris.isEmpty() || names.size != uris.size || sizes.size != uris.size) {
            synchronized(lock) { if (!looping) stopSelf(startId) }
            return START_NOT_STICKY
        }
        // Photo-picker links can be kept; Google Photos share links ride on the grant above.
        for (u in uris) runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val batch = Batch(uris.indices.map { Importer.Source(uris[it], names[it], sizes[it]) }, intent?.getStringExtra("album"), startId)
        synchronized(lock) {
            pending += batch
            total += batch.sources.size
            totalBytes += batch.sources.sumOf { it.size.coerceAtLeast(0) }
            publish()
            if (!looping) { looping = true; scope.launch { work() } }
        }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        var lastId = 0
        while (true) {
            val b = synchronized(lock) { pending.removeFirstOrNull() } ?: break
            lastId = b.startId
            val gate = Semaphore(PARALLEL)
            b.sources.map { src ->
                scope.async {
                    gate.withPermit {
                        val r = Importer.import(this@ImportService, src, b.album, status = "copied") { bytes -> copied[src.uri] = bytes; publish() }
                        synchronized(lock) {
                            done++
                            when (r) {
                                Importer.Result.ADDED -> added++
                                Importer.Result.DUPLICATE -> duplicates++
                                Importer.Result.FAILED -> failed++
                            }
                        }
                        publish()
                    }
                }
            }.awaitAll()
        }
        finish(lastId)
    }

    /** Everything is copied: hand the videos to the uploader in one go. */
    private fun finish(lastId: Int) {
        val db = Db.get(this)
        for (it in db.items("status='copied'")) db.update(it.id, "status" to "queued")
        val summary = synchronized(lock) {
            looping = false
            Triple(added, duplicates, failed).also {
                total = 0; done = 0; totalBytes = 0; copied.clear(); added = 0; duplicates = 0; failed = 0
            }
        }
        _progress.value = null
        if (summary.first > 0) {
            Prefs(this).lastPull = 0L  // check the channel first, so nothing already there goes up twice
            BackupWorker.runNow(this, after = true)
        }
        Notify.importDone(this, summary.first, summary.second, summary.third)
        stopSelf(lastId)  // a newer import that started meanwhile keeps the service going
    }

    @Volatile private var lastNote = 0L

    private fun publish() {
        val p = synchronized(lock) { Progress(done, total, copied.values.sum(), totalBytes) }
        _progress.value = p
        val now = System.currentTimeMillis()
        if (now - lastNote < 700 && p.done < p.total) return  // the system drops faster updates anyway
        lastNote = now
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { NotificationManagerCompat.from(this).notify(Notify.ID_IMPORT, notification(p)) }
    }

    private fun notification(p: Progress? = _progress.value) = Notify.progress(this, "Copying from Google Photos",
        if (p == null || p.total == 0) "Starting…"
        else "${p.done} of ${p.total} · ${BackupWorker.fmtSize(p.bytes)} of ${BackupWorker.fmtSize(p.totalBytes)}. Uploads start when all are copied.",
        if (p == null || p.totalBytes <= 0) 0 else (p.bytes * 100 / p.totalBytes).toInt().coerceIn(1, 100), screen = "uploading")

    override fun onDestroy() {
        scope.cancel()
        _progress.value = null
        super.onDestroy()
    }
}
