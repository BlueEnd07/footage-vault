package com.footagevault.app.data

/** Where each upload goes: photos and short videos to Telegram (original files), everything else to YouTube. */
object Router {
    private const val TELEGRAM_MAX = 2_000_000_000L  // Telegram's file size limit

    fun store(prefs: Prefs, kind: String, durationMs: Long, size: Long): String = when {
        kind == "photo" -> "telegram"
        prefs.tgChatId != 0L && prefs.tgShortMinutes > 0 && durationMs in 1..prefs.tgShortMinutes * 60_000L && size < TELEGRAM_MAX -> "telegram"
        else -> "youtube"
    }

    /** After Telegram is connected or the cut-off changes: re-route videos that haven't started uploading. */
    fun reroute(db: Db, prefs: Prefs) {
        for (it in db.items("status='queued' AND upload_url IS NULL AND (store IS NULL OR kind='video')")) {
            val s = store(prefs, it.kind, it.durationMs, it.size)
            if (s != it.store) db.update(it.id, "store" to s)
        }
    }
}
