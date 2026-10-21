package com.footagevault.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Works out when a video was really recorded, from its file name and metadata, so clips shot on
 * the same day sit together even when one copy lost its recording time.
 */
object TimeGuess {
    data class Guess(val ms: Long, val hasTime: Boolean)

    private val zone get() = ZoneId.systemDefault()

    // VID_20261017_175541984, PXL_20261017_123456789, NC_20261005_221701, IMG_20260914_215637,
    // dji_mimo_20260926_102332_…, 20261017_175541, 20261017175541
    private val compact = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})[_\-T ]?(\d{2})(\d{2})(\d{2})(\d{0,3})(?!\d{4})""")
    // 2026-10-17 17:55(:41) (PC vault titles), "Screen Recording 2026-10-17 at 17.55.41", 2026-10-17_17-55-41
    private val dashed = Regex("""(20\d{2})-(\d{2})-(\d{2})(?:[ _T]|\s+at\s+)(\d{2})[.:\-](\d{2})(?:[.:\-](\d{2}))?""")
    // Date only: VID-20261017-WA0012 (WhatsApp), 2026-10-17
    private val compactDate = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""")
    private val dashedDate = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)""")

    private fun at(y: String, mo: String, d: String, h: String?, mi: String?, s: String?, frac: String?): LocalDateTime? = runCatching {
        val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        if (date.isAfter(LocalDate.now().plusDays(2))) return null  // nonsense / not a date
        if (h == null) return date.atTime(LocalTime.NOON)
        val nanos = (frac?.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0) * 1_000_000
        date.atTime(h.toInt(), mi!!.toInt(), (s?.ifEmpty { null } ?: "0").toInt(), nanos)
    }.getOrNull()

    /** Time encoded in a file name or title, read as the phone's local time. */
    fun fromName(name: String): Guess? {
        compact.find(name)?.destructured?.let { (y, mo, d, h, mi, s, f) -> at(y, mo, d, h, mi, s, f)?.let { return Guess(ms(it), true) } }
        dashed.find(name)?.groupValues?.let { g -> at(g[1], g[2], g[3], g[4], g[5], g.getOrNull(6), null)?.let { return Guess(ms(it), true) } }
        compactDate.find(name)?.destructured?.let { (y, mo, d) -> at(y, mo, d, null, null, null, null)?.let { return Guess(ms(it), false) } }
        dashedDate.find(name)?.destructured?.let { (y, mo, d) -> at(y, mo, d, null, null, null, null)?.let { return Guess(ms(it), false) } }
        return null
    }

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    /**
     * Best recording time for a video found on YouTube:
     * Footage Vault's own record (exact) > time in the file name / title > YouTube's recording date
     * (if it has a time) > a date-only name or recording date (midday) > upload date.
     */
    fun forYouTube(title: String, description: String, recordingDate: String?, published: String?): Long? {
        Regex("""fv:(\{.*\})""").find(description)?.let { m ->
            runCatching { org.json.JSONObject(m.groupValues[1]).getLong("r") }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val names = buildList {
            Regex("""Original file:\s*(\S.*?)\s*$""", RegexOption.MULTILINE).find(description)?.let { add(it.groupValues[1]) }
            add(title)
        }
        val fromNames = names.mapNotNull { fromName(it) }
        fromNames.firstOrNull { it.hasTime }?.let { return it.ms }
        val rec = recordingDate?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        // YouTube keeps only the date for most uploads (00:00:00 UTC): that's a date, not a time.
        val recHasTime = rec != null && !(rec.hour == 0 && rec.minute == 0 && rec.second == 0)
        if (rec != null && recHasTime) return rec.toInstant().toEpochMilli()
        fromNames.firstOrNull()?.let { return it.ms }
        if (rec != null) return rec.toLocalDate().atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return published?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    }

    /**
     * Best time for a file on the phone. Android's "date taken" is right for camera clips, but copied,
     * downloaded or edited files often only have the date they were saved; the name then knows better.
     */
    fun forPhone(name: String, dateTaken: Long, dateAdded: Long): Long {
        val n = fromName(name)
        if (dateTaken <= 0) return n?.ms ?: dateAdded
        if (n != null && n.hasTime && kotlin.math.abs(n.ms - dateTaken) > 10 * 60_000L) return n.ms
        return dateTaken
    }
}
