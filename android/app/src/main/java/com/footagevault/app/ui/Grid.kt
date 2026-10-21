package com.footagevault.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.footagevault.app.data.Item
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE, d MMM" else "EEE, d MMM yyyy", Locale.getDefault()).format(d)
    }
}

/** Google Photos-style grid: day headers, 4 square tiles per row, long-press to select. */
@Composable
fun VideoGrid(
    items: List<Item>,
    selected: Set<Long>,
    selecting: Boolean,
    onOpen: (Item) -> Unit,
    onToggle: (Item) -> Unit,
    onToggleDay: (List<Item>) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val days = items.groupBy { localDate(it.recorded) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        header?.invoke(this)
        for ((day, list) in days) {
            item(key = "h$day", span = { GridItemSpan(maxLineSpan) }) {
                val all = selecting && list.all { it.id in selected }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Icon(if (all) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, "Select day",
                            tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onToggleDay(list) })
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(dayLabel(day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    list.firstNotNullOfOrNull { it.place }?.let {
                        Text("  ·  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(list, key = { it.id }) { item ->
                Tile(item, item.id in selected, selecting, onOpen, onToggle)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: Item, isSelected: Boolean, selecting: Boolean, onOpen: (Item) -> Unit, onToggle: (Item) -> Unit) {
    val scale by animateFloatAsState(if (isSelected) 0.86f else 1f, label = "sel")
    val shadow = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black.copy(alpha = .6f), blurRadius = 6f))
    Box(
        Modifier.aspectRatio(1f)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = { if (selecting) onToggle(item) else onOpen(item) }, onLongClick = { onToggle(item) })
    ) {
        AsyncImage(
            model = item.thumbModel, contentDescription = item.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (isSelected) 10.dp else 0.dp)),
        )
        Row(Modifier.align(Alignment.TopEnd).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDur(item.durationMs), style = shadow)
            Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        }
        if (item.favorite) Icon(Icons.Filled.Favorite, "Favourite", tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(16.dp))
        Badge(item, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        if (selecting) Icon(if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)
                .background(if (isSelected) Color.White else Color.Transparent, CircleShape))
    }
}

@Composable
private fun Badge(item: Item, modifier: Modifier) {
    val bg = Modifier.size(22.dp).background(Color.Black.copy(alpha = .55f), CircleShape).padding(4.dp)
    when {
        item.status == "uploading" -> Box(modifier.then(bg)) {
            CircularProgressIndicator(progress = { item.progress.coerceAtLeast(.05f) }, color = Color.White, strokeWidth = 2.dp, modifier = Modifier.fillMaxSize())
        }
        item.status == "queued" || item.status == "processing" ->
            Icon(Icons.Outlined.CloudUpload, "Waiting to back up", tint = Color.White, modifier = modifier.then(bg))
        item.status == "failed" ->
            Icon(Icons.Outlined.ErrorOutline, "Problem", tint = Color.White, modifier = modifier.size(22.dp).background(MaterialTheme.colorScheme.error, CircleShape).padding(3.dp))
    }
}
