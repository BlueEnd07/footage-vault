package com.footagevault.app.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live numbers of the uploads running right now, for the Uploading screen: bytes sent, speed and time left.
 * The backup job runs in the app's own process, so the screen reads them straight from here.
 */
object UploadLive {
    data class Up(
        val size: Long,
        val sent: Long,
        /** Bytes already on YouTube before this run (a resumed upload). */
        val resumedFrom: Long,
        val started: Long,
        /** Last time a piece went through. */
        val updated: Long,
        /** Bytes per second, smoothed; null until the first piece is through. */
        val speed: Double?,
    ) {
        /** Seconds left at the current speed. */
        val eta: Long? get() = speed?.takeIf { it > 0 }?.let { ((size - sent).coerceAtLeast(0) / it).toLong() }
    }

    /** Finished since the app started. */
    data class Done(val since: Long, val count: Int, val bytes: Long)

    private val _ups = MutableStateFlow<Map<Long, Up>>(emptyMap())
    val ups: StateFlow<Map<Long, Up>> = _ups.asStateFlow()
    private val _done = MutableStateFlow(Done(System.currentTimeMillis(), 0, 0))
    val done: StateFlow<Done> = _done.asStateFlow()

    fun start(id: Long, size: Long, from: Long, now: Long = System.currentTimeMillis()) =
        _ups.update { it + (id to Up(size, from, from, now, now, null)) }

    fun progress(id: Long, sent: Long, now: Long = System.currentTimeMillis()) = _ups.update { all ->
        val u = all[id] ?: return@update all
        val bytes = sent - u.sent
        val secs = (now - u.updated) / 1000.0
        val speed = if (bytes > 0 && secs > 0) (bytes / secs).let { r -> u.speed?.let { 0.6 * it + 0.4 * r } ?: r } else u.speed
        all + (id to u.copy(sent = sent, updated = now, speed = speed))
    }

    fun end(id: Long) = _ups.update { it - id }

    fun finished(bytes: Long) = _done.update { it.copy(count = it.count + 1, bytes = it.bytes + bytes) }

    /** Sum of the speeds of the uploads running side by side. */
    fun totalSpeed(): Double = _ups.value.values.sumOf { it.speed ?: 0.0 }
}

/** The user tapped Pause: the upload stops after the piece it's sending and continues from there later. */
class PausedException : Exception("Paused")
