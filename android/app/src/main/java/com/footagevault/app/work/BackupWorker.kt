package com.footagevault.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.footagevault.app.data.Db
import com.footagevault.app.data.MediaScanner
import com.footagevault.app.data.Prefs
import com.footagevault.app.net.ApiException
import com.footagevault.app.net.Auth
import com.footagevault.app.net.YouTube
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

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
        MediaScanner.scan(applicationContext)
        MediaScanner.fillLocations(applicationContext)
        if (!prefs.signedIn) return Result.success()
        val token = Auth.silentToken(applicationContext)
        if (token == null) { Notify.signInNeeded(applicationContext); return Result.success() }
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
            prefs.lastError = e.message ?: e.javaClass.simpleName
            return Result.retry()
        }
        announce()
        return Result.success()
    }

    private suspend fun uploadQueued(sync: Sync, yt: YouTube, refreshToken: suspend () -> Unit) {
        if (prefs.userPaused || System.currentTimeMillis() < prefs.pausedUntil) return
        while (!isStopped) {
            val it = db.items("status IN ('queued','uploading') AND on_phone=1 AND attempts < 3", order = "recorded ASC").firstOrNull() ?: return
            val left = db.items("status IN ('queued','uploading') AND on_phone=1").size
            val title = "Backing up · $left left"
            setForeground(foreground(title, it.name, 0))
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
                        setForegroundAsync(foreground(title, "${it.name} · $pct%", pct))
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
                db.update(it.id, "status" to "queued", "error" to (e.message ?: "Network problem"))
                throw e
            }
        }
    }

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

        private fun constraints(prefs: Prefs) = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()

        /** Every 15 minutes (Android's minimum), under the chosen Wi-Fi / charging rules. */
        fun schedule(ctx: Context, replace: Boolean = false) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(Prefs(ctx))).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** [after] = also run once more after a run that's already going (new videos were just added). */
        fun runNow(ctx: Context, after: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(Prefs(ctx))).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, if (after) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
        }

        /** YouTube's daily quota resets at midnight Pacific time. */
        fun nextQuotaReset(): Long = ZonedDateTime.now(ZoneId.of("America/Los_Angeles")).plusDays(1)
            .withHour(0).withMinute(5).withSecond(0).toInstant().toEpochMilli()

        fun fmtSize(b: Long) = if (b > 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)
    }
}
