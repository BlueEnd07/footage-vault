package com.footagevault.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One row per video: on the phone, on YouTube, or both.
 *
 * status:
 *   local       on the phone, not set to back up (older than the backup start, or folder not picked)
 *   queued      waiting to upload
 *   uploading   upload in progress (upload_url lets it resume after the app is closed)
 *   processing  uploaded, YouTube is still processing it
 *   backed_up   safely on YouTube
 *   failed      see error
 *   youtube     only on YouTube (uploaded elsewhere, or deleted from the phone)
 */
data class Item(
    val id: Long,
    val mediaId: Long?,
    val uri: String?,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val recorded: Long,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
    val bucket: String?,
    val status: String,
    val ytId: String?,
    val progress: Float,
    val error: String?,
    val uploadUrl: String?,
    val attempts: Int,
    val favorite: Boolean,
    val favSynced: Boolean,
    val onPhone: Boolean,
    val privacy: String?,
    val geoChecked: Boolean,
) {
    val isBackedUp get() = status == "backed_up" || status == "youtube"
    val isPending get() = status == "queued" || status == "uploading" || status == "processing"
    val thumbModel: Any?
        get() = if (onPhone && uri != null) android.net.Uri.parse(uri)
        else ytId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "vault.db", null, 1) {

    private val _version = MutableStateFlow(0L)
    /** Bumps on every write so the UI can reload. */
    val version: StateFlow<Long> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id INTEGER UNIQUE,
                uri TEXT,
                name TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                recorded INTEGER NOT NULL,
                lat REAL, lon REAL, place TEXT,
                bucket TEXT,
                status TEXT NOT NULL,
                yt_id TEXT UNIQUE,
                progress REAL NOT NULL DEFAULT 0,
                error TEXT,
                upload_url TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                fav_synced INTEGER NOT NULL DEFAULT 1,
                on_phone INTEGER NOT NULL DEFAULT 1,
                privacy TEXT,
                geo_checked INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_items_status ON items(status)")
        db.execSQL("CREATE INDEX idx_items_recorded ON items(recorded)")
        // Albums are the channel's playlists (cached here; YouTube is the source of truth).
        db.execSQL("CREATE TABLE albums (playlist_id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE album_items (playlist_id TEXT NOT NULL, yt_id TEXT NOT NULL, item_id TEXT,
               PRIMARY KEY (playlist_id, yt_id))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun bump() { _version.value = _version.value + 1 }

    private fun Cursor.item() = Item(
        id = getLong(getColumnIndexOrThrow("id")),
        mediaId = getLongOrNull("media_id"),
        uri = getStringOrNull("uri"),
        name = getString(getColumnIndexOrThrow("name")),
        size = getLong(getColumnIndexOrThrow("size")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        recorded = getLong(getColumnIndexOrThrow("recorded")),
        lat = getDoubleOrNull("lat"),
        lon = getDoubleOrNull("lon"),
        place = getStringOrNull("place"),
        bucket = getStringOrNull("bucket"),
        status = getString(getColumnIndexOrThrow("status")),
        ytId = getStringOrNull("yt_id"),
        progress = getFloat(getColumnIndexOrThrow("progress")),
        error = getStringOrNull("error"),
        uploadUrl = getStringOrNull("upload_url"),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        favorite = getInt(getColumnIndexOrThrow("favorite")) == 1,
        favSynced = getInt(getColumnIndexOrThrow("fav_synced")) == 1,
        onPhone = getInt(getColumnIndexOrThrow("on_phone")) == 1,
        privacy = getStringOrNull("privacy"),
        geoChecked = getInt(getColumnIndexOrThrow("geo_checked")) == 1,
    )

    private fun Cursor.getStringOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.getLongOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.getDoubleOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getDouble(it) }

    fun items(where: String? = null, args: Array<String>? = null, order: String = "recorded DESC"): List<Item> =
        readableDatabase.query("items", null, where, args, null, null, order).use { c ->
            buildList { while (c.moveToNext()) add(c.item()) }
        }

    fun item(id: Long) = items("id=?", arrayOf(id.toString())).firstOrNull()
    fun byYt(ytId: String) = items("yt_id=?", arrayOf(ytId)).firstOrNull()

    fun insert(values: ContentValues): Long = writableDatabase.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE).also { bump() }

    fun update(id: Long, values: ContentValues) {
        writableDatabase.update("items", values, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun update(id: Long, vararg pairs: Pair<String, Any?>) = update(id, cv(*pairs))

    fun delete(id: Long) {
        writableDatabase.delete("items", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun mediaIds(): Map<Long, Long> =
        readableDatabase.rawQuery("SELECT media_id, id FROM items WHERE media_id IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
        }

    // ---------- albums ----------
    data class Album(val playlistId: String, val name: String, val ytIds: List<String>)

    fun albums(): List<Album> {
        val members = mutableMapOf<String, MutableList<String>>()
        readableDatabase.rawQuery("SELECT playlist_id, yt_id FROM album_items", null).use { c ->
            while (c.moveToNext()) members.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
        }
        return readableDatabase.rawQuery("SELECT playlist_id, name FROM albums ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Album(c.getString(0), c.getString(1), members[c.getString(0)].orEmpty())) }
        }
    }

    fun replaceAlbums(list: List<Pair<String, String>>, items: Map<String, List<Pair<String, String>>>) {
        writableDatabase.transaction {
            delete("albums", null, null)
            delete("album_items", null, null)
            for ((pid, name) in list) {
                insert("albums", null, cv("playlist_id" to pid, "name" to name))
                for ((itemId, vid) in items[pid].orEmpty())
                    insertWithOnConflict("album_items", null, cv("playlist_id" to pid, "yt_id" to vid, "item_id" to itemId), SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        bump()
    }

    fun addAlbum(pid: String, name: String) {
        writableDatabase.insertWithOnConflict("albums", null, cv("playlist_id" to pid, "name" to name), SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun removeAlbum(pid: String) {
        writableDatabase.delete("albums", "playlist_id=?", arrayOf(pid))
        writableDatabase.delete("album_items", "playlist_id=?", arrayOf(pid))
        bump()
    }

    fun addToAlbum(pid: String, ytId: String, itemId: String) {
        writableDatabase.insertWithOnConflict("album_items", null, cv("playlist_id" to pid, "yt_id" to ytId, "item_id" to itemId), SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun albumItemId(pid: String, ytId: String): String? =
        readableDatabase.rawQuery("SELECT item_id FROM album_items WHERE playlist_id=? AND yt_id=?", arrayOf(pid, ytId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun removeFromAlbum(pid: String, ytId: String) {
        writableDatabase.delete("album_items", "playlist_id=? AND yt_id=?", arrayOf(pid, ytId))
        bump()
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }

        fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
            for ((k, v) in pairs) when (v) {
                null -> putNull(k)
                is String -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Float -> put(k, v)
                is Double -> put(k, v)
                is Boolean -> put(k, if (v) 1 else 0)
                else -> put(k, v.toString())
            }
        }
    }
}
