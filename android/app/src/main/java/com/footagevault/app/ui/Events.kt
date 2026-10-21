package com.footagevault.app.ui

import com.footagevault.app.data.Item
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Clips recorded less than 3 hours (and 50 km) apart form one event, like the PC app. */
data class VaultEvent(val key: String, val name: String, val items: List<Item>) {
    val cover get() = items[items.size / 2]
}

object Events {
    private const val GAP_MS = 3 * 60 * 60 * 1000L
    private const val SPLIT_KM = 50.0

    private fun km(a: Item, b: Item): Double {
        val r = Math.PI / 180
        val h = sin((b.lat!! - a.lat!!) * r / 2).let { it * it } +
            cos(a.lat * r) * cos(b.lat * r) * sin((b.lon!! - a.lon!!) * r / 2).let { it * it }
        return 2 * 6371 * asin(sqrt(h))
    }

    private fun label(start: Long, end: Long): String {
        val z = ZoneId.systemDefault()
        val s = Instant.ofEpochMilli(start).atZone(z)
        val e = Instant.ofEpochMilli(end).atZone(z)
        val year = if (s.year == java.time.Year.now().value) "" else " ${s.year}"
        if (end - start < 18 * 60 * 60 * 1000L) {
            val part = when (s.hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
            return DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(s) + "$year, $part"
        }
        return if (s.month == e.month) "${s.dayOfMonth}–${e.dayOfMonth} ${DateTimeFormatter.ofPattern("MMM").format(s)}$year"
        else "${DateTimeFormatter.ofPattern("d MMM").format(s)} – ${DateTimeFormatter.ofPattern("d MMM").format(e)}$year"
    }

    fun build(items: List<Item>): List<VaultEvent> {
        val sorted = items.sortedBy { it.recorded }
        val groups = mutableListOf<MutableList<Item>>()
        var cur = mutableListOf<Item>()
        var lastLoc: Item? = null
        for (it in sorted) {
            if (cur.isNotEmpty()) {
                val late = it.recorded - cur.last().recorded > GAP_MS
                val far = it.lat != null && lastLoc != null && km(lastLoc, it) > SPLIT_KM
                if (late || far) { groups += cur; cur = mutableListOf(); lastLoc = null }
            }
            cur += it
            if (it.lat != null) lastLoc = it
        }
        if (cur.isNotEmpty()) groups += cur
        return groups.filter { it.size >= 2 }.map { g ->
            val place = g.mapNotNull { it.place }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val when_ = label(g.first().recorded, g.last().recorded)
            VaultEvent("e${g.first().id}_${g.size}", if (place != null) "$place · $when_" else when_, g)
        }.reversed()
    }
}
