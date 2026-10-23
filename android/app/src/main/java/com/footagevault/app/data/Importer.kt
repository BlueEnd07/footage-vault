package com.footagevault.app.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.footagevault.app.data.Db.Companion.cv
import com.footagevault.app.work.Sync
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Videos shared to the app (e.g. from Google Photos: select an album's videos → Share → Footage Vault).
 * Share links only last while the share screen is open, so each video is copied into the app's own storage,
 * uploaded like a phone video, and the copy is deleted once YouTube has it (see Sync.cleanImports).
 */
object Importer {
    const val BUCKET = "Google Photos"

    data class Source(val uri: Uri, val name: String, val size: Long)
    enum class Result { ADDED, DUPLICATE, FAILED }

    fun dir(ctx: Context) = File(ctx.filesDir, "imports").apply { mkdirs() }

    /** Everything shared in the intent (one or many). */
    fun sharedUris(i: Intent): List<Uri> {
        val out = linkedSetOf<Uri>()
        if (i.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
        else IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let(out::add) }
        return out.toList()
    }

    private val VIDEO_EXT = Regex("""(?i)\.(mp4|mov|m4v|3gp|mkv|webm|avi|mts|m2ts)$""")

    /** Name and size of each shared video. Photos (and GIFs) are left out. */
    fun describe(ctx: Context, uris: List<Uri>): Pair<List<Source>, Int> {
        val out = mutableListOf<Source>()
        var skipped = 0
        for (uri in uris) {
            var name: String? = null; var size = -1L
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            val video = mime?.startsWith("video/") == true || (mime == null || mime == "application/octet-stream") && VIDEO_EXT.containsMatchIn(n)
            if (!video || MediaScanner.isGif(n)) { skipped++; continue }
            out += Source(uri, if (VIDEO_EXT.containsMatchIn(n)) n else "$n.mp4", size)
        }
        return out to skipped
    }

    private fun stems(name: String) = setOf(Sync.stem(name), Sync.stem(name.substringAfter(" · ")))

    /** Already in the library: same file name and the same size or length. */
    private fun known(db: Db, name: String, size: Long, durationMs: Long?): Boolean {
        val s = Sync.stem(name)
        return db.items().any { it ->
            s in stems(it.name) && ((size > 0 && it.size == size) || (durationMs != null && durationMs > 0 && it.durationMs > 0 &&
                Sync.sameLength(it.durationMs / 1000.0, durationMs)))
        }
    }

    /** Copies one video in, reads its details and queues it for backup (into [album] if given). */
    fun import(ctx: Context, src: Source, album: String?, status: String = "queued", onBytes: (Long) -> Unit): Result {
        val db = Db.get(ctx)
        if (known(db, src.name, src.size, null)) return Result.DUPLICATE
        val file = File(dir(ctx), "${System.currentTimeMillis()}_${src.name.replace(Regex("""[\\/:*?"<>|]"""), "_")}")
        try {
            ctx.contentResolver.openInputStream(src.uri)?.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); total += n; onBytes(total)
                    }
                }
            } ?: return Result.FAILED
        } catch (e: Exception) {
            file.delete()
            return Result.FAILED
        }

        // The file's own details: recording time, length, size on screen, GPS.
        var durationMs = 0L; var w = 0; var h = 0; var taken = 0L; var lat: Double? = null; var lon: Double? = null
        runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(file.path)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 == 90) { val t = w; w = h; h = t }
                taken = parseMp4Date(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).orEmpty())?.let { m ->
                    lat = m.groupValues[1].toDouble(); lon = m.groupValues[2].toDouble()
                }
            }
        }
        if (known(db, src.name, file.length(), durationMs)) { file.delete(); return Result.DUPLICATE }
        val recorded = TimeGuess.forPhone(src.name, taken, file.lastModified())
        db.insert(cv(
            "uri" to Uri.fromFile(file).toString(), "name" to src.name, "size" to file.length(), "duration_ms" to durationMs,
            "width" to w, "height" to h, "recorded" to recorded, "bucket" to BUCKET, "status" to status,
            "store" to Router.store(Prefs(ctx), "video", durationMs, file.length()),
            "lat" to lat, "lon" to lon, "geo_checked" to false, "pending_album" to album?.trim()?.ifBlank { null },
        ))
        return Result.ADDED
    }

    /** MP4 creation time ("20261017T175541.000Z", UTC). QuickTime's empty value is 1904: ignored. */
    fun parseMp4Date(s: String?): Long {
        val m = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(s ?: return 0) ?: return 0
        val (y, mo, d, hh, mi, ss) = m.destructured
        if (y.toInt() < 1990) return 0
        return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), hh.toInt(), mi.toInt(), ss.toInt()).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrDefault(0)
    }

    /** After the app was closed mid-import: finished copies go to the uploader, half-copied files are removed. */
    fun recover(ctx: Context) {
        val db = Db.get(ctx)
        for (it in db.items("status='copied'")) db.update(it.id, "status" to "queued")
        val used = db.items("uri LIKE 'file:%'").mapNotNull { it.uri?.let(Uri::parse)?.path }.toSet()
        dir(ctx).listFiles()?.forEach { f -> if (f.path !in used) f.delete() }
    }

    /** Deletes the app's own copy of an imported video. */
    fun deleteCopy(it: Item) { it.uri?.let(Uri::parse)?.path?.let { p -> File(p).delete() } }
}
