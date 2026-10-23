package com.footagevault.app.data

import android.content.Context
import androidx.core.content.edit

/** Small settings stored on the phone. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit { putBoolean("onboarded", v) }

    var signedIn: Boolean
        get() = p.getBoolean("signed_in", false)
        set(v) = p.edit { putBoolean("signed_in", v) }

    var channelTitle: String?
        get() = p.getString("channel_title", null)
        set(v) = p.edit { putString("channel_title", v) }

    /** Upload only on Wi-Fi (unmetered). */
    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", true)
        set(v) = p.edit { putBoolean("wifi_only", v) }

    var chargingOnly: Boolean
        get() = p.getBoolean("charging_only", false)
        set(v) = p.edit { putBoolean("charging_only", v) }

    /** "private" or "unlisted". Unlisted only works once Google has audited the API project. */
    var privacy: String
        get() = p.getString("privacy", "private") ?: "private"
        set(v) = p.edit { putString("privacy", v) }

    /** Set when YouTube turned an "unlisted" upload into "private" (project not audited). */
    /** YouTube refused to make an upload unlisted: stop asking after each upload (Studio's bulk edit works). */
    var unlistRefused: Boolean
        get() = p.getBoolean("unlist_refused", false)
        set(v) = p.edit { putBoolean("unlist_refused", v) }

    var forcedPrivate: Boolean
        get() = p.getBoolean("forced_private", false)
        set(v) = p.edit { putBoolean("forced_private", v) }

    /** Folders (MediaStore bucket names) to back up. */
    var folders: Set<String>
        get() = p.getStringSet("folders", setOf("Camera")) ?: setOf("Camera")
        set(v) = p.edit { putStringSet("folders", v) }

    /** Videos recorded before this (ms) aren't backed up automatically. 0 = everything. */
    var backupFrom: Long
        get() = p.getLong("backup_from", 0L)
        set(v) = p.edit { putLong("backup_from", v) }

    var favoritesPlaylist: String?
        get() = p.getString("fav_playlist", null)
        set(v) = p.edit { putString("fav_playlist", v) }

    /** Why the last backup run stopped (shown on the Uploading page); null = it went fine. */
    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) = p.edit { putString("last_error", v) }

    // ---------- Telegram (photos and short videos) ----------
    /** From my.telegram.org; 0 / null = Telegram not set up. Stored only on this phone. */
    var tgApiId: Int
        get() = p.getInt("tg_api_id", 0)
        set(v) = p.edit { putInt("tg_api_id", v) }
    var tgApiHash: String?
        get() = p.getString("tg_api_hash", null)
        set(v) = p.edit { putString("tg_api_hash", v) }
    /** The private "Footage Vault" channel; 0 = not set up yet. */
    var tgChatId: Long
        get() = p.getLong("tg_chat_id", 0L)
        set(v) = p.edit { putLong("tg_chat_id", v) }
    /** Videos up to this long go to Telegram (original file); 0 = all videos go to YouTube. */
    var tgShortMinutes: Int
        get() = p.getInt("tg_short_minutes", 3)
        set(v) = p.edit { putInt("tg_short_minutes", v) }

    var lastPull: Long
        get() = p.getLong("last_pull", 0L)
        set(v) = p.edit { putLong("last_pull", v) }

    /** Paused until (ms) after YouTube's daily limit. */
    var pausedUntil: Long
        get() = p.getLong("paused_until", 0L)
        set(v) = p.edit { putLong("paused_until", v) }

    var userPaused: Boolean
        get() = p.getBoolean("user_paused", false)
        set(v) = p.edit { putBoolean("user_paused", v) }

    /** "system" | "light" | "dark" | "black" (pure black for OLED screens) */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit { putString("theme", v) }

    /** Grid density chosen by pinching: 3, 4, 5 or 7 (7 = month view). */
    var gridColumns: Int
        get() = p.getInt("grid_columns", 4)
        set(v) = p.edit { putInt("grid_columns", v) }

    /** Where playback stopped (ms), per video, so long videos resume. */
    fun resumeAt(key: String): Long = r.getLong(key, 0L)
    fun setResumeAt(key: String, ms: Long) = r.edit { if (ms > 0) putLong(key, ms) else remove(key) }
    private val r = ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)

    /** Number of "safe to delete" videos already announced, so the notification isn't repeated. */
    var announcedSafe: Int
        get() = p.getInt("announced_safe", 0)
        set(v) = p.edit { putInt("announced_safe", v) }
}
