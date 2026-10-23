package com.footagevault.app.data

/** "160 videos" / "1 photo and 3 videos", for messages. */
fun countWords(list: List<Item>): String {
    val photos = list.count { it.isPhoto }
    val videos = list.size - photos
    fun n(k: Int, word: String) = "$k $word${if (k == 1) "" else "s"}"
    return listOfNotNull(if (photos > 0) n(photos, "photo") else null, if (videos > 0) n(videos, "video") else null)
        .joinToString(" and ").ifEmpty { "0 videos" }
}

/** Where these are backed up: "YouTube", "Telegram" or "YouTube and Telegram". */
fun backupPlace(list: List<Item>): String {
    val tg = list.any { it.onTelegram }
    val yt = list.any { !it.onTelegram }
    return if (tg && yt) "YouTube and Telegram" else if (tg) "Telegram" else "YouTube"
}

/** "120 photos and 40 videos are safely backed up." */
fun safelyBackedUp(list: List<Item>) = "${countWords(list)} ${if (list.size == 1) "is" else "are"} safely backed up."
