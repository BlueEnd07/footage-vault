package com.footagevault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.footagevault.app.Notice
import com.footagevault.app.PageHead
import com.footagevault.app.data.Item
import com.footagevault.app.work.BackupWorker
import com.footagevault.app.work.UploadLive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** "4 min", "1 h 20 min", "under a minute". */
internal fun fmtLeft(sec: Long): String = when {
    sec < 60 -> "under a minute"
    sec < 3600 -> "${(sec + 30) / 60} min"
    sec < 86_400 -> "${sec / 3600} h" + ((sec % 3600) / 60).let { if (it > 0) " $it min" else "" }
    else -> "${sec / 86_400} d ${(sec % 86_400) / 3600} h"
}

private fun fmtRate(b: Double) = when {
    b >= 10e6 -> "%.0f MB/s".format(b / 1e6)
    b >= 1e6 -> "%.1f MB/s".format(b / 1e6)
    else -> "%.0f KB/s".format(b / 1e3)
}
private val whenFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
private fun fmtWhen(ms: Long) = whenFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun left(it: Item) = (it.size * (1 - it.progress.coerceIn(0f, 1f))).toLong()

/**
 * The Uploading page: what's uploading right now (speed, time left), what didn't work (Retry all), what's next,
 * and what YouTube is still processing. Same layout as the PC app's Uploading page.
 */
@Composable
fun UploadsScreen(items: List<Item>, vm: AppViewModel, onOpen: (List<Item>, Int) -> Unit) {
    val ups by UploadLive.ups.collectAsState()
    val done by UploadLive.done.collectAsState()
    val paused by vm.paused.collectAsState()
    // Time moves on between pieces too ("no progress for 1 min").
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val uploading = items.filter { it.status == "uploading" }.sortedBy { it.recorded }
    val failed = items.filter { it.status == "failed" }.sortedBy { it.recorded }
    val next = items.filter { it.status == "queued" || it.status == "copied" }.sortedBy { it.recorded }
    val processing = items.filter { it.status == "processing" }.sortedBy { it.recorded }
    val all = uploading + failed + next + processing
    val open = { it: Item -> onOpen(all, all.indexOf(it)) }

    // ---- what's left, and roughly how long at the current speed
    val curLeft = uploading.sumOf { ups[it.id]?.let { u -> (u.size - u.sent).coerceAtLeast(0) } ?: left(it) }
    val bytesLeft = curLeft + next.sumOf(::left)
    val speed = uploading.sumOf { ups[it.id]?.speed ?: 0.0 }
    val toSend = uploading.size + next.size
    val moving = speed > 0 && !paused

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val title = if (toSend > 0) "${BackupWorker.fmtSize(bytesLeft)} left"
                else if (processing.isNotEmpty()) "Almost done" else if (failed.isNotEmpty()) "Some didn't back up" else "Everything is backed up"
            val sub = listOfNotNull(
                if (toSend > 0) com.footagevault.app.data.countWords(uploading + next) else null,
                if (moving && toSend > 0) "about ${fmtLeft((bytesLeft / speed).toLong())} at ${fmtRate(speed)}" else null,
                if (processing.isNotEmpty()) "${processing.size} processing on YouTube" else null,
                if (paused && toSend > 0) "Paused" else null,
            ).joinToString(" · ").ifEmpty { if (toSend > 0) "Waiting to start" else "Nothing waiting" }
            PageHead(title, sub) {
                if (toSend > 0) FilledTonalButton(onClick = { vm.setPaused(!paused) }) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (paused) "Resume" else "Pause")
                }
            }
        }
        // Nothing moving: say why, with the one thing to do about it.
        if (uploading.isEmpty() && next.isNotEmpty()) item {
            val (t, why) = vm.uploadState(items)
            Notice(if (paused) Icons.Outlined.Pause else Icons.Outlined.CloudUpload, t, why,
                if (paused) "Resume" else "Back up now") { if (paused) vm.setPaused(false) else vm.backUpNow() }
        }
        uploading.forEach { up -> item(key = "now${up.id}") { NowCard(up, ups[up.id], paused, now) { open(up) } } }

        section("Didn't back up", "Usually a network hiccup", failed,
            action = { TextButton(onClick = vm::retryAll) { Text("Retry all") } }) { _, it ->
            VideoRow(it, open, trail = {}, note = {
                Note(Icons.Outlined.ErrorOutline, (it.error ?: "Upload failed") + if (!it.onPhone) " · no longer on the phone" else "", error = true)
            })
        }
        var ahead = curLeft
        section("Up next", "Oldest videos go first", next) { i, it ->
            val startsIn = if (moving) fmtLeft((ahead / speed).toLong()) else null
            ahead += left(it)
            VideoRow(it, open, trail = {
                when {
                    it.status == "copied" -> TrailText("Copying…")
                    i == 0 && uploading.isNotEmpty() -> Tag("Next")
                    startsIn != null -> TrailText("in ~$startsIn")
                }
            }, note = {
                if (it.progress > 0f && it.progress < 1f) Note(Icons.Outlined.Sync, "Continues from ${(it.progress * 100).toInt()}%")
                if (it.error != null) Note(Icons.Outlined.ErrorOutline, "${if (it.attempts >= 3) "Stopped after 3 tries" else "Try ${it.attempts + 1} of 3"} · ${it.error}", error = true)
            })
        }
        section("Processing on YouTube", "Usually a few minutes, then it's backed up", processing) { _, it ->
            VideoRow(it, open, trail = { TrailText("Processing", Icons.Outlined.Sync) })
        }
        if (done.count > 0) item {
            Row(Modifier.padding(start = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudDone, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("${done.count} ${if (done.count == 1) "video" else "videos"} (${BackupWorker.fmtSize(done.bytes)}) backed up since " +
                    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(done.since).atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A heading like the date headers in the grid, then the rows as one rounded block. */
private fun LazyListScope.section(
    title: String, hint: String, list: List<Item>, action: @Composable () -> Unit = {},
    row: @Composable (Int, Item) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h$title") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Text("${list.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
    itemsIndexed(list, key = { _, it -> "$title${it.id}" }) { i, it ->
        val big = 20.dp; val small = 4.dp
        val shape = RoundedCornerShape(
            topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
            bottomStart = if (i == list.lastIndex) big else small, bottomEnd = if (i == list.lastIndex) big else small,
        )
        Box(Modifier.padding(horizontal = 12.dp, vertical = 1.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            row(i, it)
        }
    }
}

@Composable
private fun VideoRow(it: Item, open: (Item) -> Unit, trail: @Composable () -> Unit, note: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable { open(it) }.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Thumb(it, Modifier.width(88.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${BackupWorker.fmtSize(it.size)} · ${fmtDur(it.durationMs)} · ${fmtWhen(it.recorded)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            note()
        }
        Spacer(Modifier.width(8.dp))
        trail()
    }
}

@Composable
private fun Thumb(it: Item, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        AsyncImage(model = it.thumbModel, contentDescription = it.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun NowCard(it: Item, u: UploadLive.Up?, paused: Boolean, now: Long, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val size = u?.size ?: it.size
    val sent = u?.sent ?: (it.size * it.progress).toLong()
    val pct = if (size > 0) (sent.toFloat() / size).coerceIn(0f, 1f) else 0f
    Surface(shape = RoundedCornerShape(20.dp), color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(it, Modifier.width(112.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(16.dp), tint = c.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Uploading now", style = MaterialTheme.typography.labelMedium, color = c.primary)
                    }
                    Text(it.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${fmtWhen(it.recorded)} · ${fmtDur(it.durationMs)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Text(when {
                    paused -> "Pausing…"
                    u?.speed != null -> "${u.eta?.let { e -> fmtLeft(e) + " left · " } ?: ""}${fmtRate(u.speed)}"
                    else -> "Measuring speed…"
                }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).height(6.dp),
                color = c.primary, trackColor = c.surfaceContainerHighest,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${BackupWorker.fmtSize(sent)} of ${BackupWorker.fmtSize(size)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (u != null && u.resumedFrom > 0 && size > 0) {
                    val from = (u.resumedFrom * 100 / size).toInt()
                    Note(Icons.Outlined.Sync, if (from > 0) "Continued from $from%" else "Picked up where it stopped")
                }
            }
            val idle = u?.let { (now - it.updated) / 1000 } ?: 0
            if (!paused && idle > 45) Box(Modifier.padding(start = 4.dp, top = 4.dp)) {
                Note(Icons.Outlined.ErrorOutline, "No progress for ${if (idle < 60) "$idle s" else fmtLeft(idle)}, retrying by itself", error = true)
            }
        }
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrailText(text: String, icon: ImageVector? = null) = Row(verticalAlignment = Alignment.CenterVertically) {
    icon?.let { Icon(it, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Tag(text: String) = Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
}
