package com.footagevault.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.footagevault.app.data.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val monthShort = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

/** Where each tile is on screen, so the viewer can grow out of it and shrink back into it. */
object TileBounds { val map = HashMap<Long, Rect>() }

/** Pinch levels: fewer columns = bigger tiles. 7 columns switches to month headers. */
val GRID_LEVELS = listOf(3, 4, 5, 7)

/**
 * Google Photos-style grid: day headers (month headers when zoomed out), pinch to change size,
 * a date scroller on the right edge, long-press to select.
 */
@Composable
fun VideoGrid(
    items: List<Item>,
    selected: Set<Long>,
    selecting: Boolean,
    columns: Int,
    onColumns: (Int) -> Unit,
    onOpen: (Item) -> Unit,
    onToggle: (Item) -> Unit,
    onToggleDay: (List<Item>) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val months = columns >= 7
    val groups: List<Pair<Any, List<Item>>> = remember(items, months) {
        if (months) items.groupBy { YearMonth.from(localDate(it.recorded)) }.toList()
        else items.groupBy { localDate(it.recorded) }.toList()
    }
    // Big tiles now and then (like Google Photos), only at the roomy sizes.
    val laid: List<Pair<Any, List<Seg>>> = remember(groups, columns) { groups.map { (k, list) -> k to mosaic(list, columns) } }
    // Recorded time per grid position (headers included), for the date scroller's label.
    val positions: List<Long?> = remember(laid, header != null) {
        buildList {
            if (header != null) add(null)
            for ((_, segs) in laid) {
                add(segs.first().let { (it as? Seg.Plain)?.items?.first() ?: (it as Seg.Block).big }.recorded)
                for (seg in segs) when (seg) { is Seg.Plain -> seg.items.forEach { add(it.recorded) }; is Seg.Block -> add(seg.big.recorded) }
            }
        }
    }
    val state = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val currentColumns by androidx.compose.runtime.rememberUpdatedState(columns)

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // Pinch: two fingers apart = bigger tiles, together = smaller. One change per pinch.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var done = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.none { it.pressed }) break
                        if (ev.changes.count { it.pressed } >= 2) {
                            ev.changes.forEach { it.consume() }
                            if (done) continue
                            zoom *= ev.calculateZoom()
                            val i = GRID_LEVELS.indexOf(currentColumns).coerceAtLeast(0)
                            val to = when {
                                zoom > 1.25f && i > 0 -> GRID_LEVELS[i - 1]
                                zoom < 0.8f && i < GRID_LEVELS.size - 1 -> GRID_LEVELS[i + 1]
                                else -> null
                            }
                            if (to != null) { onColumns(to); haptics.performHapticFeedback(HapticFeedbackType.LongPress); done = true }
                        }
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(if (months) 1.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(if (months) 1.dp else 2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            header?.invoke(this)
            for ((key, segs) in laid) {
                val list = segs.flatMap { seg -> when (seg) { is Seg.Plain -> seg.items; is Seg.Block -> listOf(seg.big) + seg.small } }
                item(key = "h$key", span = { GridItemSpan(maxLineSpan) }) {
                    val all = selecting && list.all { it.id in selected }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = if (months) 20.dp else 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            Icon(if (all) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, "Select",
                                tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onToggleDay(list) })
                            Spacer(Modifier.width(10.dp))
                        }
                        if (key is YearMonth) Text(monthFmt.format(key), style = MaterialTheme.typography.titleMedium)
                        else Text(dayLabel(key as LocalDate), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        if (!months) list.firstNotNullOfOrNull { it.place }?.let {
                            Text("  ·  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                for (seg in segs) when (seg) {
                    is Seg.Plain -> items(seg.items, key = { it.id }) { item -> Tile(item, item.id in selected, selecting, compact = months, onOpen, onToggle) }
                    is Seg.Block -> item(key = "b${seg.big.id}", span = { GridItemSpan(maxLineSpan) }) {
                        MosaicBlock(seg, columns, gap = 2.dp, selected, selecting, onOpen, onToggle)
                    }
                }
            }
        }
        if (items.size > 30) DateScroller(state, positions)
    }
}

/** A day's tiles: plain rows, or a block with one big (2×2) tile beside small ones. */
sealed interface Seg {
    data class Plain(val items: List<Item>) : Seg
    data class Block(val big: Item, val small: List<Item>, val bigLeft: Boolean) : Seg
}

/**
 * Lays out one day. At 3 or 4 columns, a day with 6+ items gets a big tile at the start and then every few rows,
 * alternating sides. Videos and favourites are picked to be big first (a big video plays by itself).
 */
private fun mosaic(list: List<Item>, columns: Int): List<Seg> {
    if (columns !in 3..4 || list.size < 6) return listOf(Seg.Plain(list))
    val block = 1 + 2 * (columns - 2)
    val out = mutableListOf<Seg>()
    var plain = mutableListOf<Item>()
    var i = 0; var row = 0; var left = true
    while (i < list.size) {
        if (list.size - i >= block && row % 4 == 0) {
            if (plain.isNotEmpty()) { out += Seg.Plain(plain); plain = mutableListOf() }
            val chunk = list.subList(i, i + block)
            val big = chunk.maxBy { (if (!it.isPhoto) 2 else 0) + (if (it.favorite) 1 else 0) }
            out += Seg.Block(big, chunk.filter { it.id != big.id }, left)
            left = !left; i += block; row += 2
        } else {
            val take = minOf(columns, list.size - i)
            plain += list.subList(i, i + take); i += take; row += 1
        }
    }
    if (plain.isNotEmpty()) out += Seg.Plain(plain)
    return out
}

@Composable
private fun MosaicBlock(seg: Seg.Block, columns: Int, gap: Dp, selected: Set<Long>, selecting: Boolean,
                        onOpen: (Item) -> Unit, onToggle: (Item) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - gap * (columns - 1)) / columns
        val bigSize = cell * 2 + gap
        val bigTile = @Composable {
            Tile(seg.big, seg.big.id in selected, selecting, compact = false, onOpen, onToggle, Modifier.size(bigSize), autoplay = !selecting)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (seg.bigLeft) bigTile()
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in seg.small.chunked(columns - 2)) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (it in r) Tile(it, it.id in selected, selecting, compact = false, onOpen, onToggle, Modifier.size(cell))
                }
            }
            if (!seg.bigLeft) bigTile()
        }
    }
}

/**
 * A big tile's video plays by itself, silently and on a loop, a moment after it shows up (phone files only).
 * It fades in over the thumbnail once the first frame is drawn, and stops when scrolled away.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AutoPreview(item: Item) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var start by remember(item.id) { mutableStateOf(false) }
    var shown by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) { delay(1500); start = true }
    if (!start || item.uri == null) return
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "preview")
    val player = remember(item.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(item.uri)))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { shown = true }
            })
            prepare(); playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(item.id) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
        androidx.media3.ui.PlayerView(c).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            this.player = player
        }
    }, modifier = Modifier.fillMaxSize().alpha(alpha))
}

/** Drag handle on the right edge; shows the month while dragging, like Google Photos. */
@Composable
private fun DateScroller(state: androidx.compose.foundation.lazy.grid.LazyGridState, positions: List<Long?>) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) recentlyScrolled = true else { delay(1500); recentlyScrolled = false }
    }
    val visible = dragging || recentlyScrolled
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "scroller")
    val total = state.layoutInfo.totalItemsCount.coerceAtLeast(1)
    val frac = if (dragging) dragFrac else state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)
    val fracNow by androidx.compose.runtime.rememberUpdatedState(frac)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val thumbH = 44.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }
        val y = (frac.coerceIn(0f, 1f) * trackPx).roundToInt()
        // Only the handle takes touches (and only while it's showing), so the edge still scrolls normally.
        if (alpha > 0f) Surface(
            shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.size(width = 30.dp, height = thumbH).alpha(alpha)
                .pointerInput(total, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; dragFrac = fracNow },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        dragFrac = (dragFrac + dy / trackPx).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * (total - 1)).roundToInt()) }
                    }
                },
        ) {}
        if (dragging) {
            val idx = (dragFrac * (positions.size - 1)).roundToInt().coerceIn(0, (positions.size - 1).coerceAtLeast(0))
            val ms = positions.drop(idx).firstOrNull { it != null } ?: positions.lastOrNull { it != null }
            if (ms != null) Surface(
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 52.dp.roundToPx() }, y) },
            ) {
                Text(monthShort.format(YearMonth.from(localDate(ms))), color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: Item, isSelected: Boolean, selecting: Boolean, compact: Boolean, onOpen: (Item) -> Unit, onToggle: (Item) -> Unit,
         modifier: Modifier = Modifier, autoplay: Boolean = false) {
    val scale by animateFloatAsState(if (isSelected) 0.86f else 1f, label = "sel")
    val shadow = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black.copy(alpha = .6f), blurRadius = 6f))
    Box(
        modifier.aspectRatio(1f)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .onGloballyPositioned { TileBounds.map[item.id] = it.boundsInWindow() }
            .combinedClickable(onClick = { if (selecting) onToggle(item) else onOpen(item) }, onLongClick = { onToggle(item) })
    ) {
        AsyncImage(
            model = item.thumbModel, contentDescription = item.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (isSelected) 10.dp else 0.dp)),
        )
        if (autoplay && !item.isPhoto && item.onPhone) Box(Modifier.fillMaxSize().scale(scale)) { AutoPreview(item) }
        if (!compact && !item.isPhoto) Row(Modifier.align(Alignment.TopEnd).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtDur(item.durationMs), style = shadow)
            Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        }
        androidx.compose.animation.AnimatedVisibility(item.favorite, Modifier.align(Alignment.BottomStart),
            enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            Icon(Icons.Filled.Favorite, "Favourite", tint = Color.White,
                modifier = Modifier.padding(if (compact) 2.dp else 5.dp).size(if (compact) 12.dp else 16.dp))
        }
        Badge(item, compact, Modifier.align(Alignment.BottomEnd).padding(if (compact) 2.dp else 5.dp))
        if (selecting) Icon(if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(if (compact) 16.dp else 22.dp)
                .background(if (isSelected) Color.White else Color.Transparent, CircleShape))
    }
}

/** Backup state on the tile. Red = not backed up yet (like a gallery's "not synced" mark). */
@Composable
private fun Badge(item: Item, compact: Boolean, modifier: Modifier) {
    val s = if (compact) 14.dp else 22.dp
    val red = Color(0xFFD93025)
    when (item.status) {
        "uploading" -> Box(modifier.size(s).background(Color.Black.copy(alpha = .55f), CircleShape).padding(if (compact) 2.dp else 4.dp)) {
            CircularProgressIndicator(progress = { item.progress.coerceAtLeast(.05f) }, color = Color.White,
                strokeWidth = if (compact) 1.5.dp else 2.dp, modifier = Modifier.fillMaxSize())
        }
        "processing" -> Icon(Icons.Outlined.CloudUpload, "Processing on YouTube", tint = Color.White,
            modifier = modifier.size(s).background(Color.Black.copy(alpha = .55f), CircleShape).padding(if (compact) 2.dp else 4.dp))
        "local", "queued", "excluded" -> Icon(Icons.Outlined.CloudOff, "Not backed up", tint = Color.White,
            modifier = modifier.size(s).background(red, CircleShape).padding(if (compact) 2.dp else 4.dp))
        "failed" -> Icon(Icons.Outlined.ErrorOutline, "Backup failed", tint = Color.White,
            modifier = modifier.size(s).background(red, CircleShape).padding(if (compact) 1.dp else 3.dp))
        "backed_up" -> if (item.onTelegram && !compact) Icon(Icons.AutoMirrored.Outlined.Send, "On Telegram", tint = Color.White,
            modifier = modifier.size(18.dp).background(Color.Black.copy(alpha = .45f), CircleShape).padding(4.dp))
    }
}
