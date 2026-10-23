package com.footagevault.app.work

import android.content.Context
import com.footagevault.app.data.Db
import com.footagevault.app.data.Item
import com.footagevault.app.data.Prefs
import com.footagevault.app.data.TimeGuess
import com.footagevault.app.net.ApiException
import com.footagevault.app.net.VideoInfo
import com.footagevault.app.net.YouTube
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps the phone's library in step with YouTube:
 *  - recognises phone videos that are already on the channel (e.g. uploaded by the PC vault) so they aren't uploaded twice
 *  - adds channel videos the phone doesn't have (uploaded elsewhere) as YouTube-only items
 *  - mirrors playlists as albums and the "Favourites" playlist as favourites
 */
class Sync(private val ctx: Context, private val yt: YouTube) {
    private val db = Db.get(ctx)
    private val prefs = Prefs(ctx)

    companion object {
        const val FAV_TITLE = "Favourites · Footage Vault"
        private val ORIGINAL = Regex("""Original file:\s*(\S.*?)\s*$""", RegexOption.MULTILINE)
        private val FV = Regex("""fv:(\{.*\})""")

        fun stem(name: String) = name.substringBeforeLast('.').lowercase()

        /** File names a channel video could have come from, read from its title/description. */
        fun sourceNames(v: VideoInfo): Set<String> {
            val out = mutableSetOf<String>()
            ORIGINAL.find(v.description)?.let { out += stem(it.groupValues[1]) }
            FV.find(v.description)?.let { runCatching { out += stem(JSONObject(it.groupValues[1]).getString("n")) } }
            out += stem(v.title.substringAfter(" · "))  // PC vault titles: "2026-10-17 17:55 · VID_…"
            out += stem(v.title)
            return out
        }

        fun sameLength(a: Double, bMs: Long) = abs(a - bMs / 1000.0) <= max(2.0, bMs / 1000.0 * 0.01)

        fun parseTime(s: String?): Long? = s?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }
    }

    /** Full pull from YouTube (about 1 quota unit per 50 videos). */
    suspend fun pull() {
        val (title, uploads) = yt.channel()
        prefs.channelTitle = title
        if (uploads.isNullOrBlank()) return
        val ids = yt.uploadIds(uploads)
        val infos = yt.videos(ids).associateBy { it.id }
        matchLocal(infos.values)

        // Channel videos the phone doesn't know: show them as YouTube-only.
        val known = db.items().mapNotNull { it.ytId }.toSet()
        for (v in infos.values) {
            if (v.id in known || v.uploadStatus !in setOf("processed", "uploaded")) continue
            db.insert(Db.cv(
                "name" to (v.title.ifBlank { v.id }), "duration_ms" to (v.durationS * 1000).toLong(),
                "recorded" to (TimeGuess.forYouTube(v.title, v.description, v.recorded, v.published) ?: System.currentTimeMillis()),
                "status" to "youtube", "yt_id" to v.id, "on_phone" to false, "privacy" to v.privacy, "geo_checked" to true,
            ))
        }
        // Deleted on YouTube: drop YouTube-only entries; phone copies just lose their link.
        for (it in db.items("yt_id IS NOT NULL")) {
            if (it.ytId in infos) {
                // Picks up privacy changed in YouTube Studio (e.g. private -> unlisted, which plays in the app).
                val v = infos.getValue(it.ytId!!)
                if (v.privacy != null && v.privacy != it.privacy) db.update(it.id, "privacy" to v.privacy)
                // Re-date videos that only live on YouTube with the best time we can find (name, details).
                if (!it.onPhone && it.dateSynced) {  // a hand-set date waiting to be saved wins
                    val best = TimeGuess.forYouTube(v.title, v.description, v.recorded, v.published)
                    if (best != null && kotlin.math.abs(best - it.recorded) > 60_000) db.update(it.id, "recorded" to best)
                }
                continue
            }
            if (it.status == "processing" || it.status == "uploading") continue
            if (!it.onPhone) db.delete(it.id)
            else db.update(it.id, "yt_id" to null, "status" to "queued", "error" to "Not on YouTube any more; uploading again")
        }
        pullPlaylists()
        prefs.lastPull = System.currentTimeMillis()
    }

    /** Phone videos already on the channel (same file name + length): mark them backed up instead of uploading. */
    private fun matchLocal(infos: Collection<VideoInfo>) {
        val byName = mutableMapOf<String, MutableList<VideoInfo>>()
        for (v in infos) for (n in sourceNames(v)) byName.getOrPut(n) { mutableListOf() } += v
        val taken = db.items("yt_id IS NOT NULL").mapNotNull { it.ytId }.toMutableSet()
        for (it in db.items("yt_id IS NULL AND on_phone=1 AND kind='video' AND (store IS NULL OR store='youtube')")) {
            val m = byName[stem(it.name)]?.firstOrNull { v -> v.id !in taken && sameLength(v.durationS, it.durationMs) } ?: continue
            taken += m.id
            // A YouTube-only row for the same video is replaced by the phone row.
            db.byYt(m.id)?.let { row -> if (!row.onPhone) db.delete(row.id) }
            db.update(it.id, "yt_id" to m.id, "status" to if (m.uploadStatus == "processed") "backed_up" else "processing",
                "privacy" to m.privacy, "upload_url" to null, "error" to null)
        }
    }

    private suspend fun pullPlaylists() {
        val lists = yt.playlists()
        val fav = lists.firstOrNull { it.second == FAV_TITLE }?.first ?: prefs.favoritesPlaylist
        prefs.favoritesPlaylist = fav
        val members = mutableMapOf<String, List<Pair<String, String>>>()
        for ((pid, _) in lists) members[pid] = yt.playlistItems(pid)
        db.replaceAlbums(lists.filter { it.first != fav }, members)
        // Favourites playlist -> favourite flags (only where nothing is waiting to be pushed)
        if (fav != null) {
            val favIds = members[fav].orEmpty().map { it.second }.toSet()
            for (it in db.items("yt_id IS NOT NULL AND fav_synced=1")) {
                val want = it.ytId in favIds
                if (want != it.favorite) db.update(it.id, "favorite" to want)
            }
        }
    }

    /** Pushes favourite changes made on the phone to the Favourites playlist. */
    suspend fun pushFavourites() {
        val pending = db.items("fav_synced=0 AND yt_id IS NOT NULL")
        if (pending.isEmpty()) return
        var fav = prefs.favoritesPlaylist
        if (fav == null) { fav = yt.createPlaylist(FAV_TITLE); prefs.favoritesPlaylist = fav }
        val current = yt.playlistItems(fav).associate { it.second to it.first }
        for (it in pending) {
            val vid = it.ytId ?: continue
            if (it.favorite && vid !in current) yt.addToPlaylist(fav, vid)
            if (!it.favorite) current[vid]?.let { itemId -> yt.removeFromPlaylist(itemId) }
            db.update(it.id, "fav_synced" to true)
        }
    }

    /** Writes hand-set dates ("Change date") to YouTube, so every device shows the same day. */
    suspend fun pushDates() {
        for (it in db.items("date_synced=0 AND yt_id IS NOT NULL")) {
            try {
                yt.setRecorded(it.ytId!!, it.recorded, it.name)
            } catch (e: ApiException) {
                if (!e.isNotFound) throw e  // gone from YouTube: nothing to update
            }
            db.update(it.id, "date_synced" to true)
        }
    }

    /** Videos shared in with an album name: into that album (made if needed) once they're on YouTube. */
    suspend fun pushPendingAlbums() {
        val pending = db.items("pending_album IS NOT NULL AND yt_id IS NOT NULL")
        for ((name, list) in pending.groupBy { it.pendingAlbum!! }) {
            val pid = db.albums().firstOrNull { it.name.equals(name, ignoreCase = true) }?.playlistId
                ?: yt.createPlaylist(name).also { db.addAlbum(it, name) }
            val have = db.albums().firstOrNull { it.playlistId == pid }?.ytIds.orEmpty().toSet()
            for (it in list) {
                if (it.ytId !in have) db.addToAlbum(pid, it.ytId!!, yt.addToPlaylist(pid, it.ytId))
                db.update(it.id, "pending_album" to null)
            }
        }
    }

    /** Copies shared in from Google Photos are deleted once YouTube has finished with them. */
    fun cleanImports() {
        for (it in db.items("uri LIKE 'file:%' AND status='backed_up'")) {
            com.footagevault.app.data.Importer.deleteCopy(it)
            db.update(it.id, "on_phone" to false, "uri" to null)
        }
    }

    /** Uploaded videos YouTube has finished processing become "backed up". */
    suspend fun checkProcessing() {
        val waiting = db.items("status='processing' AND yt_id IS NOT NULL")
        if (waiting.isEmpty()) return
        val infos = yt.videos(waiting.mapNotNull { it.ytId }).associateBy { it.id }
        for (it in waiting) {
            val v = infos[it.ytId]
            when {
                v == null -> db.update(it.id, "status" to "failed", "error" to "Video not found on YouTube")
                v.uploadStatus == "processed" -> db.update(it.id, "status" to "backed_up", "privacy" to v.privacy, "error" to null)
                v.uploadStatus in setOf("failed", "rejected", "deleted") ->
                    db.update(it.id, "status" to "failed", "error" to "YouTube ${v.uploadStatus}: ${v.failure ?: ""}".trim())
            }
        }
    }

    /** Re-checks against YouTube right before deleting from the phone. Returns the ones that are safe. */
    suspend fun verifySafe(items: List<Item>): List<Item> {
        val infos = yt.videos(items.mapNotNull { it.ytId }).associateBy { it.id }
        return items.filter { it ->
            val v = infos[it.ytId] ?: return@filter false
            v.uploadStatus == "processed" && sameLength(v.durationS, it.durationMs)
        }
    }

    /** Upload metadata. The description carries the file's details so any device can rebuild the library. */
    fun uploadMeta(it: Item): JSONObject {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(it.recorded).atZone(zone)
        val title = "${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(t)} · ${it.name.substringBeforeLast('.')}"
            .replace("<", "").replace(">", "").take(100)
        val fv = JSONObject().put("n", it.name).put("r", it.recorded).put("s", it.size).put("d", it.durationMs)
        if (it.lat != null && it.lon != null) fv.put("lat", it.lat).put("lon", it.lon)
        val rec = JSONObject().put("recordingDate", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t))
        if (it.lat != null && it.lon != null) rec.put("location", JSONObject().put("latitude", it.lat).put("longitude", it.lon))
        return JSONObject()
            .put("snippet", JSONObject().put("title", title).put("categoryId", "22")
                .put("description", "Original file: ${it.name}\n\nBacked up by Footage Vault\nfv:$fv"))
            .put("status", JSONObject().put("privacyStatus", prefs.privacy).put("selfDeclaredMadeForKids", false))
            .put("recordingDetails", rec)
    }

    fun isQuota(e: Throwable) = e is ApiException && e.isQuota
}
