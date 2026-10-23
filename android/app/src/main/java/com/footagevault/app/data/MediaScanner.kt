package com.footagevault.app.data

import android.content.ContentUris
import android.content.Context
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.footagevault.app.data.Db.Companion.cv
import java.util.Locale

/** Reads the phone's videos (and photos, once Telegram is set up) from MediaStore (the system gallery index). */
object MediaScanner {

    data class Folder(val name: String, val count: Int)

    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val photoCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Photos are backed up to Telegram, so they're only scanned once Telegram is set up and photo access is allowed. */
    fun photosOn(ctx: Context) = Prefs(ctx).tgChatId != 0L &&
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Gallery folders that contain videos, with counts (for Settings → folders). */
    fun folders(ctx: Context): List<Folder> {
        val counts = mutableMapOf<String, Int>()
        for (col in if (photosOn(ctx)) listOf(collection, photoCollection) else listOf(collection))
            ctx.contentResolver.query(col, arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val b = c.getString(0) ?: continue
                    if (isGif(b)) continue
                    counts[b] = (counts[b] ?: 0) + 1
                }
            }
        return counts.map { Folder(it.key, it.value) }.sortedByDescending { it.count }
    }

    fun countToBackUp(ctx: Context, folders: Set<String>, from: Long): Int {
        var n = 0
        query(ctx) { row -> if (row.bucket in folders && row.recorded >= from) n++ }
        return n
    }

    /** WhatsApp / keyboard "GIFs" are saved as tiny videos (e.g. "WhatsApp Animated Gifs"): not footage, so skipped. */
    private val GIF = Regex("""(?i)(^|[^a-z])gifs?([^a-z]|$)""")
    fun isGif(text: String?) = text != null && GIF.containsMatchIn(text)

    private data class Row(val id: Long, val name: String, val size: Long, val durationMs: Long, val width: Int,
                           val height: Int, val recorded: Long, val bucket: String?, val kind: String, val uri: String)

    private fun query(ctx: Context, photos: Boolean = photosOn(ctx), each: (Row) -> Unit) {
        query(ctx, collection, "video", each)
        if (photos) query(ctx, photoCollection, "photo", each)
    }

    private fun query(ctx: Context, col: android.net.Uri, kind: String, each: (Row) -> Unit) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            if (kind == "video") MediaStore.Video.Media.DURATION else MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        ctx.contentResolver.query(col, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(10) == 1 || c.getInt(11) == 1) continue  // still being written / in the trash
                val size = c.getLong(2)
                if (size <= 0) continue
                var w = c.getInt(4); var h = c.getInt(5)
                if (c.getInt(6) % 180 == 90) { val t = w; w = h; h = t }
                val name = c.getString(1) ?: if (kind == "video") "video.mp4" else "photo.jpg"
                if (isGif(name) || isGif(c.getString(9)) || c.getString(12) == "image/gif") continue
                // Date taken, unless it's missing or clearly the copy/save time and the name says otherwise.
                val recorded = TimeGuess.forPhone(name, c.getLong(7), c.getLong(8) * 1000)
                val id = c.getLong(0)
                each(Row(id, name, size, if (kind == "video") c.getLong(3) else 0L, w, h, recorded, c.getString(9), kind,
                    ContentUris.withAppendedId(col, id).toString()))
            }
        }
    }

    /**
     * Adds new phone videos, and notices ones that were deleted from the phone.
     * Returns how many new videos were queued for backup.
     */
    fun scan(ctx: Context): Int {
        val db = Db.get(ctx)
        val prefs = Prefs(ctx)
        val known = db.mediaIds()
        val seen = HashSet<Long>()
        var queued = 0
        val photos = photosOn(ctx)
        query(ctx, photos) { r ->
            seen += r.id
            val existing = known[r.id]
            if (existing != null) {
                // Earlier versions dated some copied files by when they were saved: fix in place.
                val row = db.item(existing)
                if (row != null && !row.manualDate && kotlin.math.abs(row.recorded - r.recorded) > 60_000) db.update(existing, "recorded" to r.recorded)
                return@query
            }
            val backup = r.bucket in prefs.folders && r.recorded >= prefs.backupFrom
            db.insert(cv(
                "media_id" to r.id,
                "uri" to r.uri,
                "name" to r.name, "size" to r.size, "duration_ms" to r.durationMs,
                "width" to r.width, "height" to r.height, "recorded" to r.recorded, "bucket" to r.bucket,
                "status" to if (backup) "queued" else "local",
                "kind" to r.kind, "store" to Router.store(prefs, r.kind, r.durationMs, r.size),
            ))
            if (backup) queued++
        }
        // Gone from the phone (deleted in the gallery, or by "Free up space"), or a GIF (no longer listed)
        for ((mediaId, id) in known) {
            if (mediaId in seen) continue
            val it = db.item(id) ?: continue
            if (it.isPhoto && !photos) continue  // photos weren't scanned this time (Telegram off): not gone
            if (it.ytId != null || it.tgMsg != null) db.update(id, "on_phone" to false, "media_id" to null, "uri" to null,
                "status" to if (it.status == "backed_up") "backed_up" else it.status)
            else db.delete(id)  // never backed up: nothing left to show
        }
        // Folder picked later / backup start moved back: queue what now qualifies.
        for (it in db.items("status='local'")) {
            if (it.bucket in prefs.folders && it.recorded >= prefs.backupFrom) { db.update(it.id, "status" to "queued"); queued++ }
        }
        Router.reroute(db, prefs)
        return queued
    }

    /** GPS from inside the video file (needs ACCESS_MEDIA_LOCATION) + nearest town name. */
    fun fillLocations(ctx: Context, limit: Int = 30) {
        val db = Db.get(ctx)
        val geocoder = if (Geocoder.isPresent()) Geocoder(ctx, Locale.getDefault()) else null
        for (it in db.items("geo_checked=0 AND on_phone=1 AND uri IS NOT NULL").take(limit)) {
            var lat: Double? = null; var lon: Double? = null; var place: String? = null
            try {
                val uri = android.net.Uri.parse(it.uri).let { u -> if (it.isImport) u else MediaStore.setRequireOriginal(u) }
                if (it.isPhoto) ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val ll = FloatArray(2)
                    if (android.media.ExifInterface(input).getLatLong(ll)) { lat = ll[0].toDouble(); lon = ll[1].toDouble() }
                } else MediaMetadataRetriever().use { mmr ->
                    mmr.setDataSource(ctx, uri)
                    val loc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(loc.orEmpty())?.let { m ->
                        lat = m.groupValues[1].toDouble(); lon = m.groupValues[2].toDouble()
                    }
                }
                if (lat != null && lon != null && geocoder != null) {
                    @Suppress("DEPRECATION")
                    val a = geocoder.getFromLocation(lat!!, lon!!, 1)?.firstOrNull()
                    place = a?.subLocality ?: a?.locality ?: a?.subAdminArea ?: a?.adminArea
                }
            } catch (_: Exception) { }
            db.update(it.id, "lat" to lat, "lon" to lon, "place" to place, "geo_checked" to true)
        }
    }
}
