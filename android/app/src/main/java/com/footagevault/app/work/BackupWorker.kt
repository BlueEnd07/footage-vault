package com.footagevault.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.footagevault.app.data.Db
import com.footagevault.app.data.Importer
import com.footagevault.app.data.MediaScanner
import com.footagevault.app.data.Prefs
import com.footagevault.app.net.ApiException
import com.footagevault.app.net.Auth
import com.footagevault.app.net.Telegram
import com.footagevault.app.net.TelegramException
import com.footagevault.app.net.YouTube
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex

/** Scans the gallery, uploads queued videos to YouTube, and keeps the library in sync. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val db = Db.get(ctx)
    private val prefs = Prefs(ctx)
    private var problem: String? = null

    /** A side step (favourites, dates, albums) that fails must not stop the uploads. */
    private suspend fun step(block: suspend () -> Unit) {
        try { block() } catch (e: ApiException) {
            if (e.isQuota || e.isAuth) throw e
            problem = e.message
        }
    }

    override suspend fun doWork(): Result {
        if (!ImportService.running) Importer.recover(applicationContext)
        runCatching { MediaScanner.scan(applicationContext) }
        runCatching { MediaScanner.fillLocations(applicationContext) }
        // Photos and short videos: Telegram, independent of YouTube (and of its daily limit).
        try { telegramUploads() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (!isStopped) problem = "Telegram: ${e.message ?: e.javaClass.simpleName}"
        }
        if (!prefs.signedIn) { prefs.lastError = problem; return Result.success() }
        val token = Auth.silentToken(applicationContext)
        if (token == null) {
            prefs.lastError = "Google sign-in has expired. Open Settings, tap Sign out, then Sign in again."
            Notify.signInNeeded(applicationContext)
            return Result.success()
        }
        var current: String = token
        val yt = YouTube { current }
        val sync = Sync(applicationContext, yt)
        try {
            // Know what's on the channel before uploading, so nothing goes up twice.
            if (System.currentTimeMillis() - prefs.lastPull > 30 * 60_000L || db.items("status='queued'").isNotEmpty() && prefs.lastPull == 0L)
                sync.pull()
            step { sync.pushFavourites() }
            step { sync.pushDates() }
            step { sync.checkProcessing() }
            uploadQueued(sync, yt) { current = Auth.silentToken(applicationContext) ?: current }
            step { sync.checkProcessing() }
            step { sync.pushPendingAlbums() }
            sync.cleanImports()
            prefs.lastError = problem
        } catch (e: ApiException) {
            if (e.isQuota) prefs.pausedUntil = nextQuotaReset()
            else if (e.isAuth) Notify.signInNeeded(applicationContext)
            prefs.lastError = if (e.isQuota) null else e.message
        } catch (e: Exception) {
            if (!isStopped) prefs.lastError = e.message ?: e.javaClass.simpleName  // stopped by Android: not a problem
            return Result.retry()
        }
        announce()
        return Result.success()
    }

    private suspend fun uploadQueued(sync: Sync, yt: YouTube, refreshToken: suspend () -> Unit) {
        if (prefs.userPaused || System.currentTimeMillis() < prefs.pausedUntil) return
        if (ImportService.running) return  // copying from Google Photos first; uploads start when it's done
        // The periodic run and a "now" run can overlap: only one uploads, so no video goes up twice.
        if (!uploading.tryLock()) return
        try { uploadLoop(sync, yt, refreshToken) } finally { uploading.unlock() }
    }

    private suspend fun uploadLoop(sync: Sync, yt: YouTube, refreshToken: suspend () -> Unit) {
        while (!isStopped) {
            val it = db.items("status IN ('queued','uploading') AND on_phone=1 AND attempts < 3 AND $YOUTUBE", order = "recorded ASC").firstOrNull() ?: return
            val left = db.items("status IN ('queued','uploading') AND on_phone=1 AND $YOUTUBE").size
            val title = "Backing up · $left left"
            val shown = tryForeground(title, it.name)
            db.update(it.id, "status" to "uploading", "error" to null)
            try {
                refreshToken()
                var session = it.uploadUrl
                var start = session?.let { s -> yt.uploadedBytes(s, it.size) }
                if (session == null || start == null) {
                    session = yt.startUpload(sync.uploadMeta(it), it.size)
                    start = 0L
                    db.update(it.id, "upload_url" to session)
                }
                var lastPct = -1
                val result = yt.uploadFrom(session, applicationContext.contentResolver, Uri.parse(it.uri), it.size, start) { sent ->
                    val pct = (sent * 100 / it.size).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        db.update(it.id, "progress" to sent.toFloat() / it.size)
                        if (shown) setForegroundAsync(foreground(title, "${it.name} · $pct%", pct))
                    }
                }
                val vid = result.getString("id")
                val privacy = result.optJSONObject("status")?.optString("privacyStatus")
                if (prefs.privacy == "unlisted" && privacy == "private") prefs.forcedPrivate = true
                db.update(it.id, "status" to "processing", "yt_id" to vid, "upload_url" to null, "progress" to 1f, "privacy" to privacy)
            } catch (e: ApiException) {
                if (e.isQuota) {
                    db.update(it.id, "status" to "queued")
                    prefs.pausedUntil = nextQuotaReset()
                    return
                }
                if (e.code == 404 || e.code == 410) db.update(it.id, "upload_url" to null)  // session expired: start over
                fail(it.id, it.attempts, e.message)
            } catch (e: java.io.FileNotFoundException) {
                db.update(it.id, "status" to "failed", "error" to "The file is no longer on the phone")
            } catch (e: Exception) {
                // Network dropped: keep the session, resume next time.
                db.update(it.id, "status" to "queued", "error" to if (isStopped) null else (e.message ?: "Network problem"))
                throw e
            }
        }
    }

    private suspend fun telegramUploads() {
        if (prefs.userPaused || ImportService.running) return
        if (db.items("status IN ('queued','uploading') AND on_phone=1 AND attempts < 3 AND store='telegram'").isEmpty()) return
        if (!Telegram.ready(applicationContext)) {
            problem = "Photos and short videos wait for Telegram. Open Settings → Telegram to sign in."
            return
        }
        if (!tgUploading.tryLock()) return
        try { telegramLoop() } finally { tgUploading.unlock() }
    }

    private suspend fun telegramLoop() {
        while (!isStopped) {
            val it = db.items("status IN ('queued','uploading') AND on_phone=1 AND attempts < 3 AND store='telegram'", order = "recorded ASC")
                .firstOrNull() ?: return
            val left = db.items("status IN ('queued','uploading') AND on_phone=1 AND store='telegram'").size
            val title = "Backing up to Telegram · $left left"
            val shown = tryForeground(title, it.name)
            db.update(it.id, "status" to "uploading", "error" to null, "progress" to 0f)
            try {
                var lastPct = -1
                val sent = Telegram.upload(applicationContext, it) { bytes ->
                    val pct = if (it.size > 0) (bytes * 100 / it.size).toInt().coerceIn(0, 100) else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        db.update(it.id, "progress" to pct / 100f)
                        if (shown) setForegroundAsync(foreground(title, "${it.name} · $pct%", pct))
                    }
                }
                db.update(it.id, "status" to "backed_up", "tg_msg" to sent.messageId, "thumb" to sent.thumb, "progress" to 1f)
            } catch (e: TelegramException) {
                if (e.isFlood) {  // Telegram asks to slow down: try again on the next run
                    db.update(it.id, "status" to "queued")
                    problem = "Telegram asked to slow down. It continues by itself shortly."
                    return
                }
                fail(it.id, it.attempts, "Telegram: ${e.message}")
            } catch (e: java.io.FileNotFoundException) {
                db.update(it.id, "status" to "failed", "error" to "The file is no longer on the phone")
            } catch (e: Exception) {
                db.update(it.id, "status" to "queued", "error" to if (isStopped) null else (e.message ?: "Network problem"))
                throw e
            }
        }
    }

    /**
     * Shows the "Backing up" notification so a long upload may keep going. Android refuses that while the app is in
     * the background (Android 12+); then the upload runs anyway, as a normal background job. If Android stops the job
     * after its time is up, the upload resumes where it left off next time.
     */
    private suspend fun tryForeground(title: String, text: String): Boolean = try {
        setForeground(foreground(title, text, 0)); true
    } catch (_: IllegalStateException) { false }

    private fun fail(id: Long, attempts: Int, msg: String?) {
        val n = attempts + 1
        db.update(id, "status" to if (n >= 3) "failed" else "queued", "attempts" to n, "error" to (msg ?: "Upload failed"))
        if (n >= 3) Notify.problem(applicationContext, db.items("status='failed'").size)
    }

    /** "Safe to delete" notification for newly backed-up phone videos. */
    private fun announce() {
        val safe = db.items("status='backed_up' AND on_phone=1")
        if (safe.size > prefs.announcedSafe) {
            val bytes = safe.sumOf { it.size }
            Notify.safeToDelete(applicationContext, safe.size, fmtSize(bytes))
        }
        prefs.announcedSafe = safe.size
    }

    private fun foreground(title: String, text: String, pct: Int) = ForegroundInfo(
        Notify.ID_PROGRESS, Notify.progress(applicationContext, title, text, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        private const val PERIODIC = "backup-periodic"
        private const val NOW = "backup-now"
        private val uploading = Mutex()
        private val tgUploading = Mutex()
        /** Rows that go to YouTube (older rows have no store yet). */
        private const val YOUTUBE = "(store IS NULL OR store='youtube')"

        private fun constraints(prefs: Prefs) = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()

        /** Every 15 minutes (Android's minimum), under the chosen Wi-Fi / charging rules. */
        fun schedule(ctx: Context, replace: Boolean = false) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(Prefs(ctx)))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * Runs a backup now. A run that's waiting (for Wi-Fi, or to retry after an error, which can grow to hours)
         * is replaced by a fresh one; a run that's going is left alone ([after] = run once more when it ends).
         */
        fun runNow(ctx: Context, after: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            val req = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(Prefs(ctx)))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build()
            val infos = wm.getWorkInfosForUniqueWork(NOW)
            infos.addListener({
                val running = runCatching { infos.get() }.getOrNull().orEmpty().any { it.state == WorkInfo.State.RUNNING }
                val policy = when {
                    !running -> ExistingWorkPolicy.REPLACE
                    after -> ExistingWorkPolicy.APPEND_OR_REPLACE
                    else -> ExistingWorkPolicy.KEEP
                }
                wm.enqueueUniqueWork(NOW, policy, req)
            }, Runnable::run)
        }

        /** YouTube's daily quota resets at midnight Pacific time. */
        fun nextQuotaReset(): Long = ZonedDateTime.now(ZoneId.of("America/Los_Angeles")).plusDays(1)
            .withHour(0).withMinute(5).withSecond(0).toInstant().toEpochMilli()

        fun fmtSize(b: Long) = if (b > 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)
    }
}
