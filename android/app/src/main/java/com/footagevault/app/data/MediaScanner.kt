package com.footagevault.app.data

import android.content.ContentUris
import android.content.Context
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.footagevault.app.data.Db.Companion.cv
import java.util.Locale

/** Reads the phone's videos from MediaStore (the system gallery index). */
object MediaScanner {

    data class Folder(val name: String, val count: Int)

    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Gallery folders that contain videos, with counts (for Settings → folders). */
    fun folders(ctx: Context): List<Folder> {
        val counts = mutableMapOf<String, Int>()
        ctx.contentResolver.query(collection, arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
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
                           val height: Int, val recorded: Long, val bucket: String?)

    private fun query(ctx: Context, each: (Row) -> Unit) {
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.ORIENTATION, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.IS_TRASHED,
        )
        ctx.contentResolver.query(collection, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(10) == 1 || c.getInt(11) == 1) continue  // still being written / in the trash
                val size = c.getLong(2)
                if (size <= 0) continue
                var w = c.getInt(4); var h = c.getInt(5)
                if (c.getInt(6) % 180 == 90) { val t = w; w = h; h = t }
                val name = c.getString(1) ?: "video.mp4"
                if (isGif(name) || isGif(c.getString(9))) continue
                // Date taken, unless it's missing or clearly the copy/save time and the name says otherwise.
                val recorded = TimeGuess.forPhone(name, c.getLong(7), c.getLong(8) * 1000)
                each(Row(c.getLong(0), name, size, c.getLong(3), w, h, recorded, c.getString(9)))
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
        query(ctx) { r ->
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
                "uri" to ContentUris.withAppendedId(collection, r.id).toString(),
                "name" to r.name, "size" to r.size, "duration_ms" to r.durationMs,
                "width" to r.width, "height" to r.height, "recorded" to r.recorded, "bucket" to r.bucket,
                "status" to if (backup) "queued" else "local",
            ))
            if (backup) queued++
        }
        // Gone from the phone (deleted in the gallery, or by "Free up space"), or a GIF (no longer listed)
        for ((mediaId, id) in known) {
            if (mediaId in seen) continue
            val it = db.item(id) ?: continue
            if (it.ytId != null) db.update(id, "on_phone" to false, "media_id" to null, "uri" to null,
                "status" to if (it.status == "backed_up") "backed_up" else it.status)
            else db.delete(id)  // never backed up: nothing left to show
        }
        // Folder picked later / backup start moved back: queue what now qualifies.
        for (it in db.items("status='local'")) {
            if (it.bucket in prefs.folders && it.recorded >= prefs.backupFrom) { db.update(it.id, "status" to "queued"); queued++ }
        }
        return queued
    }

    /** GPS from inside the video file (needs ACCESS_MEDIA_LOCATION) + nearest town name. */
    fun fillLocations(ctx: Context, limit: Int = 30) {
        val db = Db.get(ctx)
        val geocoder = if (Geocoder.isPresent()) Geocoder(ctx, Locale.getDefault()) else null
        for (it in db.items("geo_checked=0 AND on_phone=1 AND uri IS NOT NULL").take(limit)) {
            var lat: Double? = null; var lon: Double? = null; var place: String? = null
            try {
                val uri = MediaStore.setRequireOriginal(android.net.Uri.parse(it.uri))
                MediaMetadataRetriever().use { mmr ->
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
