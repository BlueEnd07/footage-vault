package com.footagevault.app.net

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val body: String) : IOException("YouTube $code: ${body.take(300)}") {
    val isQuota get() = code in listOf(403, 429) && listOf("quotaExceeded", "uploadLimitExceeded", "rateLimitExceeded").any { it in body }
    val isNotFound get() = code == 404
    val isAuth get() = code == 401
}

data class VideoInfo(
    val id: String, val title: String, val description: String, val privacy: String?, val uploadStatus: String?,
    val failure: String?, val durationS: Double, val recorded: String?, val published: String?,
)

/** Minimal YouTube Data API v3 client (plain HTTPS + JSON). */
class YouTube(private val token: suspend () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val api = "https://www.googleapis.com/youtube/v3"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun call(method: String, url: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val rb = body?.toString()?.toRequestBody(json) ?: if (method == "POST" || method == "PUT") "".toRequestBody(json) else null
        val req = Request.Builder().url(url).method(method, rb).header("Authorization", "Bearer ${token()}").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun pages(base: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page: String? = null
        do {
            val r = call("GET", base + "&maxResults=50" + (page?.let { "&pageToken=$it" } ?: ""))
            r.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) out += a.getJSONObject(i) }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    // ---------- channel ----------
    suspend fun channel(): Pair<String?, String?> {
        val it = call("GET", "$api/channels?part=snippet,contentDetails&mine=true").optJSONArray("items")?.optJSONObject(0)
            ?: return null to null
        return it.getJSONObject("snippet").optString("title") to
            it.getJSONObject("contentDetails").getJSONObject("relatedPlaylists").optString("uploads")
    }

    suspend fun uploadIds(uploadsPlaylist: String): List<String> =
        pages("$api/playlistItems?part=contentDetails&playlistId=${enc(uploadsPlaylist)}")
            .map { it.getJSONObject("contentDetails").getString("videoId") }

    suspend fun videos(ids: List<String>): List<VideoInfo> = ids.chunked(50).flatMap { chunk ->
        val a = call("GET", "$api/videos?part=snippet,status,contentDetails,recordingDetails&id=${enc(chunk.joinToString(","))}")
            .optJSONArray("items") ?: JSONArray()
        (0 until a.length()).map { i ->
            val v = a.getJSONObject(i)
            val sn = v.optJSONObject("snippet") ?: JSONObject()
            val st = v.optJSONObject("status") ?: JSONObject()
            VideoInfo(
                id = v.getString("id"), title = sn.optString("title"), description = sn.optString("description"),
                privacy = st.optString("privacyStatus").ifBlank { null }, uploadStatus = st.optString("uploadStatus").ifBlank { null },
                failure = (st.optString("failureReason").ifBlank { st.optString("rejectionReason") }).ifBlank { null },
                durationS = isoDuration(v.optJSONObject("contentDetails")?.optString("duration").orEmpty()),
                recorded = v.optJSONObject("recordingDetails")?.optString("recordingDate")?.ifBlank { null },
                published = sn.optString("publishedAt").ifBlank { null },
            )
        }
    }

    // ---------- playlists (albums) ----------
    suspend fun playlists(): List<Pair<String, String>> =
        pages("$api/playlists?part=snippet&mine=true").map { it.getString("id") to it.getJSONObject("snippet").optString("title") }

    /** [(playlist item id, video id)] */
    suspend fun playlistItems(pid: String): List<Pair<String, String>> =
        pages("$api/playlistItems?part=contentDetails&playlistId=${enc(pid)}")
            .map { it.getString("id") to it.getJSONObject("contentDetails").getString("videoId") }

    suspend fun createPlaylist(title: String): String = call("POST", "$api/playlists?part=snippet,status", JSONObject()
        .put("snippet", JSONObject().put("title", title.take(150)).put("description", "Footage Vault album"))
        .put("status", JSONObject().put("privacyStatus", "private"))).getString("id")

    suspend fun renamePlaylist(pid: String, title: String) {
        call("PUT", "$api/playlists?part=snippet", JSONObject().put("id", pid)
            .put("snippet", JSONObject().put("title", title.take(150)).put("description", "Footage Vault album")))
    }

    suspend fun deletePlaylist(pid: String) { call("DELETE", "$api/playlists?id=${enc(pid)}") }

    suspend fun addToPlaylist(pid: String, videoId: String): String = call("POST", "$api/playlistItems?part=snippet", JSONObject()
        .put("snippet", JSONObject().put("playlistId", pid)
            .put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", videoId)))).getString("id")

    suspend fun removeFromPlaylist(itemId: String) { call("DELETE", "$api/playlistItems?id=${enc(itemId)}") }

    /** Permanently deletes a video from the channel. */
    suspend fun deleteVideo(id: String) { call("DELETE", "$api/videos?id=${enc(id)}") }

    // ---------- resumable upload ----------
    /** Starts an upload session; the returned URL can be resumed later, even after a restart. */
    suspend fun startUpload(meta: JSONObject, size: Long): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status,recordingDetails")
            .post(meta.toString().toRequestBody(json))
            .header("Authorization", "Bearer ${token()}")
            .header("X-Upload-Content-Length", size.toString())
            .header("X-Upload-Content-Type", "video/*")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string().orEmpty())
            r.header("Location") ?: throw IOException("No upload URL")
        }
    }

    /** Bytes YouTube already has for this session, or null if the session expired. */
    suspend fun uploadedBytes(session: String, size: Long): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(session).put(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer ${token()}").header("Content-Range", "bytes */$size").build()
        http.newCall(req).execute().use { r ->
            when (r.code) {
                308 -> r.header("Range")?.substringAfterLast("-")?.toLongOrNull()?.plus(1) ?: 0L
                200, 201 -> size
                else -> null
            }
        }
    }

    /**
     * Sends the file from [start] in 8 MB chunks. Returns the finished video's JSON.
     */
    suspend fun uploadFrom(session: String, cr: ContentResolver, uri: Uri, size: Long, start: Long,
                           onProgress: (Long) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val chunk = 8L * 1024 * 1024
        var pos = start
        cr.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < pos) { val s = input.skip(pos - skipped); if (s <= 0) break; skipped += s }
            val buf = ByteArray(chunk.toInt())
            while (true) {
                val n = readFully(input, buf)
                if (n <= 0 && pos < size) throw IOException("File ended early")
                val end = pos + n - 1
                val body = object : RequestBody() {
                    override fun contentType() = "video/*".toMediaType()
                    override fun contentLength() = n.toLong()
                    override fun writeTo(sink: BufferedSink) { sink.write(buf, 0, n) }
                }
                val req = Request.Builder().url(session).put(body)
                    .header("Authorization", "Bearer ${token()}")
                    .header("Content-Range", "bytes $pos-$end/$size").build()
                http.newCall(req).execute().use { r ->
                    when (r.code) {
                        200, 201 -> return@withContext JSONObject(r.body?.string().orEmpty())
                        308 -> {
                            val got = r.header("Range")?.substringAfterLast("-")?.toLongOrNull()?.plus(1) ?: (end + 1)
                            if (got != end + 1) throw IOException("Upload out of sync; will resume")
                            pos = got
                            onProgress(pos)
                        }
                        else -> throw ApiException(r.code, r.body?.string().orEmpty())
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") throw IOException("unreachable")
        } ?: throw IOException("Can't open the video file")
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    companion object {
        fun isoDuration(v: String): Double {
            val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(v) ?: return 0.0
            val (d, h, mi, s) = m.destructured
            return ((((d.toIntOrNull() ?: 0) * 24 + (h.toIntOrNull() ?: 0)) * 60 + (mi.toIntOrNull() ?: 0)) * 60 + (s.toIntOrNull() ?: 0)).toDouble()
        }
    }
}
