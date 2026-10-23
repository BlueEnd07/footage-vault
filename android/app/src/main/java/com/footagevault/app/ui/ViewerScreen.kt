package com.footagevault.app.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Image
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.footagevault.app.YouTubePlayerActivity
import com.footagevault.app.data.Item
import com.footagevault.app.work.BackupWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plays in YouTube's player inside the app: not on the phone, and unlisted / public. */
fun Item.playsInYouTubePlayer() = !onPhone && ytId != null && privacy != null && privacy != "private"

/** Full-screen YouTube player for [list], starting at [index]; swiping there moves through the same list. */
fun youTubePlayerIntent(ctx: Context, list: List<Item>, index: Int): Intent {
    val ids = list.map { if (it.playsInYouTubePlayer()) it.ytId!! else "" }
    val labels = list.map { x ->
        val t = Instant.ofEpochMilli(x.recorded).atZone(ZoneId.systemDefault())
        listOfNotNull(dayLabel(t.toLocalDate()), DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(t), x.place).joinToString(" · ")
    }
    return YouTubePlayerActivity.intent(ctx, ids.toTypedArray(), labels.toTypedArray(), index, list.map { it.id }.toLongArray())
}

fun openOnYouTube(ctx: Context, ytId: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$ytId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$ytId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Shares the phone's file, or the YouTube link once the video only lives on YouTube. */
fun shareItems(ctx: Context, items: List<Item>): String? {
    val files = items.filter { it.onPhone && it.uri != null }.map { it ->
        // Google Photos copies are the app's own files: shared through the FileProvider.
        if (it.isImport) androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", java.io.File(Uri.parse(it.uri).path!!))
        else Uri.parse(it.uri)
    }
    val links = items.filter { !(it.onPhone && it.uri != null) && it.ytId != null }
    val type = if (items.any { it.isPhoto }) "*/*" else "video/*"
    val intent = when {
        files.size == 1 && links.isEmpty() -> Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, files[0])
        files.isNotEmpty() && links.isEmpty() -> Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
        links.isNotEmpty() && files.isEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, links.joinToString("\n") { "https://youtu.be/${it.ytId}" })
        files.isNotEmpty() -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
        else -> return "Nothing to share yet"
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return when {
        files.isNotEmpty() && links.isNotEmpty() -> "Shared the ${files.size} on this phone; the others are only on YouTube"
        links.any { it.privacy == "private" } -> "Private videos: only you can open these links"
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    list: List<Item>, start: Int, vm: AppViewModel,
    onClose: () -> Unit, onAddToAlbum: (Item) -> Unit, onDelete: (Item) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) { list.size }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var changingDate by remember { mutableStateOf(false) }
    val current = list.getOrNull(pager.currentPage) ?: return onClose()
    // Live version of the current item (favourite / status changes)
    val all by vm.items.collectAsState()
    val live = all.firstOrNull { it.id == current.id } ?: current

    // Grow out of the tapped tile, shrink back into the tile on close (like Google Photos).
    val enter = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { enter.animateTo(0f, tween(220, easing = FastOutSlowInEasing)); onClose() }
    }
    BackHandler(onBack = ::close)

    // YouTube player window: swipes there move through this same list; when it closes, follow it here.
    val ytLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val i = r.data?.getIntExtra(YouTubePlayerActivity.EXTRA_INDEX, -1) ?: -1
        if (i in list.indices) scope.launch { pager.scrollToPage(i) }
    }
    fun playOnYouTubeHere(page: Int) =
        ytLauncher.launch(youTubePlayerIntent(ctx, list.map { x -> all.firstOrNull { it.id == x.id } ?: x }, page))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = with(density) { maxHeight.toPx() }
        val screenW = with(density) { maxWidth.toPx() }
        val drag = (dragY / screenH).coerceIn(0f, 1f)
        val shown = enter.value
        // Background fades as the video flies in / is dragged down.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (shown * (1f - drag * 1.4f)).coerceIn(0f, 1f))))

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = ((shown - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    translationY = dragY
                    val s = 1f - drag * 0.35f
                    scaleX = s; scaleY = s
                }
                .pointerInput(Unit) {
                    // Swipe down to close. Only clear downward drags are taken; sideways goes to the pager.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var taken = false
                        var total = Offset.Zero
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ev.changes.count { it.pressed } > 1) break
                            total += ch.positionChange()
                            if (!taken && total.y > 28.dp.toPx() && total.y > 2 * abs(total.x)) taken = true
                            if (taken) { ch.consume(); dragY = total.y.coerceAtLeast(0f) }
                        }
                        if (taken) {
                            if (dragY > 140.dp.toPx()) close()
                            else scope.launch { Animatable(dragY).animateTo(0f, tween(180)) { dragY = value } }
                        }
                    }
                }
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { list[it].id }) { page ->
                val item = all.firstOrNull { it.id == list[page].id } ?: list[page]
                val isCurrent = page == pager.currentPage
                when {
                    !item.onPhone && item.tgMsg != null && isCurrent -> TelegramView(item, vm.prefs)
                    item.isPhoto && item.onPhone && item.uri != null -> PhotoView(item)
                    isCurrent && item.onPhone && item.uri != null -> LocalPlayer(item, vm.prefs)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(item.thumbModel, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        if (!item.onPhone && item.tgMsg != null) FilledTonalButton(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.footagevault.app.net.Telegram.link(ctx, item.tgMsg))))
                        }) { Text("Open in Telegram") }
                        if (!item.onPhone && item.ytId != null) {
                            if (item.playsInYouTubePlayer()) {
                                Surface(onClick = { playOnYouTubeHere(page) }, shape = CircleShape, color = Color.Black.copy(.55f), modifier = Modifier.size(76.dp)) {
                                    Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.padding(16.dp))
                                }
                            } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                // YouTube only plays private videos in its own app.
                                FilledTonalButton(onClick = { openOnYouTube(ctx, item.ytId) }) {
                                    Icon(Icons.Outlined.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text("Play on YouTube")
                                }
                                Text("Private video. Make it unlisted in YouTube Studio to play it here.", color = Color.White,
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 10.dp).background(Color.Black.copy(.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }

            // Top bar
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(.6f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp).alpha(1f - drag * 3f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                val t = Instant.ofEpochMilli(live.recorded).atZone(ZoneId.systemDefault())
                Column(Modifier.weight(1f)) {
                    Text(dayLabel(t.toLocalDate()), color = Color.White, fontWeight = FontWeight.Medium)
                    Text(listOfNotNull(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(t), live.place).joinToString(" · "),
                        color = Color(0xFFC4C7C5), style = MaterialTheme.typography.bodySmall)
                }
                if (live.ytId != null) IconButton(onClick = { openOnYouTube(ctx, live.ytId) }) { Icon(Icons.Outlined.OpenInNew, "Open on YouTube", tint = Color.White) }
            }

            // Bottom bar
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.7f))))
                    .navigationBarsPadding().padding(vertical = 6.dp).alpha(1f - drag * 3f),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                BarButton(Icons.Outlined.Share, "Share") { shareItems(ctx, listOf(live))?.let(vm::say) }
                BarButton(if (live.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favourite",
                    if (live.favorite) Color(0xFFF28B82) else Color.White) { vm.setFavorite(listOf(live.id), !live.favorite) }
                BarButton(Icons.Outlined.Info, "Info") { showInfo = true }
                BarButton(Icons.Outlined.LibraryAdd, "Add to") { onAddToAlbum(live) }
                BarButton(Icons.Outlined.Delete, "Delete") { onDelete(live) }
            }
        }

        // The flying thumbnail while opening / closing.
        if (shown < 1f) {
            val from = TileBounds.map[live.id]
            if (from != null) {
                val full = Rect(0f, 0f, screenW, screenH)
                val r = lerp(from, full, shown)
                AsyncImage(live.thumbModel, null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(r.left.roundToInt(), (r.top + dragY * shown).roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .alpha(if (closing) 1f else (1f - ((shown - 0.7f) / 0.3f)).coerceIn(0f, 1f)))
            }
        }
    }

    if (showInfo) ModalBottomSheet(onDismissRequest = { showInfo = false }) { InfoSheet(live) { showInfo = false; changingDate = true } }
    if (changingDate) ChangeDateDialog(listOf(live), onDone = { d, t -> vm.setDate(listOf(live), d, t); changingDate = false },
        onDismiss = { changingDate = false })
}

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
)

@Composable
private fun BarButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = tint)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Not on the phone any more: fetch the original from Telegram (kept in its cache), then show or play it. */
@Composable
private fun TelegramView(item: Item, prefs: com.footagevault.app.data.Prefs) {
    val ctx = LocalContext.current
    var path by remember(item.id) { mutableStateOf<String?>(null) }
    var progress by remember(item.id) { mutableFloatStateOf(0f) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        try { path = com.footagevault.app.net.Telegram.download(ctx, item) { progress = it } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't load it from Telegram" }
    }
    val local = path?.let { item.copy(uri = Uri.fromFile(java.io.File(it)).toString(), onPhone = true) }
    when {
        local != null && item.isPhoto -> PhotoView(local)
        local != null -> LocalPlayer(local, prefs)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(item.thumbModel, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(Color.Black.copy(.55f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                if (error == null) {
                    CircularProgressIndicator(progress = { progress.coerceAtLeast(.03f) }, color = Color.White)
                    Text("Loading from Telegram… ${(progress * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                } else {
                    Text(error!!, color = Color.White, textAlign = TextAlign.Center)
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.footagevault.app.net.Telegram.link(ctx, item.tgMsg!!))))
                    }, modifier = Modifier.padding(top = 10.dp)) { Text("Open in Telegram") }
                }
            }
        }
    }
}

/** A photo: pinch to zoom, double-tap for 2.5×. Swiping between photos works while it isn't zoomed. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoView(item: Item) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    AsyncImage(
        model = Uri.parse(item.uri), contentDescription = item.name, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(item.id) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

/** Phone video: resumes where it stopped, double-tap left/right to skip 10 seconds. */
@Composable
private fun LocalPlayer(item: Item, prefs: com.footagevault.app.data.Prefs) {
    val ctx = LocalContext.current
    val key = "m${item.id}"
    var flash by remember { mutableStateOf<Pair<Int, Long>?>(null) }  // (-1 | 1, when)
    val player = remember(item.id) {
        ExoPlayer.Builder(ctx).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
            val at = prefs.resumeAt(key)
            if (at > 0) seekTo(at)
            playWhenReady = true
        }
    }
    DisposableEffect(item.id) {
        onDispose {
            val pos = player.currentPosition; val dur = player.duration
            // Remember the spot in videos longer than a minute, unless it was (nearly) finished.
            prefs.setResumeAt(key, if (dur > 60_000 && pos > 5_000 && pos < dur - 5_000) pos else 0)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            PlayerView(c).apply {
                this.player = player
                setShowNextButton(false); setShowPreviousButton(false)
                val detector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val dir = if (e.x < width / 2f) -1 else 1
                        if (dir < 0) player.seekBack() else player.seekForward()
                        flash = dir to System.currentTimeMillis()
                        return true
                    }
                })
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
            }
        }, modifier = Modifier.fillMaxSize())
        flash?.let { (dir, at) ->
            LaunchedEffect(at) { delay(650); if (flash?.second == at) flash = null }
            Surface(shape = CircleShape, color = Color.Black.copy(.5f),
                modifier = Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 36.dp)) {
                Text(if (dir < 0) "« 10s" else "10s »", color = Color.White, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun InfoSheet(it: Item, onChangeDate: () -> Unit) {
    val t = Instant.ofEpochMilli(it.recorded).atZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        InfoRow(Icons.Outlined.CalendarMonth, DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(t),
            DateTimeFormatter.ofPattern("h:mm a · O", Locale.getDefault()).format(t) + if (it.manualDate) " · set by you" else "") {
            IconButton(onClick = onChangeDate) { Icon(Icons.Outlined.EditCalendar, "Change date") }
        }
        val meta = listOfNotNull(if (it.width > 0) "${it.width} × ${it.height}" else null,
            if (it.size > 0) BackupWorker.fmtSize(it.size) else null, if (it.isPhoto) null else fmtDur(it.durationMs)).joinToString(" · ")
        InfoRow(if (it.isPhoto) Icons.Outlined.Image else Icons.Outlined.Movie, it.name, meta)
        if (it.place != null) InfoRow(Icons.Outlined.Place, it.place, "%.4f, %.4f".format(it.lat, it.lon))
        val (icon, text) = when (it.status) {
            "backed_up", "youtube" -> Icons.Outlined.CloudDone to if (it.onTelegram) "Backed up to Telegram (original file)" else "Backed up to YouTube"
            "failed" -> Icons.Outlined.ErrorOutline to "Backup failed"
            "local" -> Icons.Outlined.Smartphone to "Only on this phone (folder not backed up)"
            "copied" -> Icons.Outlined.CloudUpload to "Copied · uploads once the rest are copied"
            "processing" -> Icons.Outlined.CloudUpload to "Uploaded · YouTube is processing"
            "uploading" -> Icons.Outlined.CloudUpload to "Uploading · ${(it.progress * 100).toInt()}%"
            else -> Icons.Outlined.CloudUpload to "Waiting to back up"
        }
        InfoRow(icon, text, listOfNotNull(it.privacy?.let { p -> "YouTube: $p" }, it.error).joinToString(" · ").ifBlank { null })
        if (it.isImport) InfoRow(Icons.Outlined.Smartphone, "Copied from Google Photos", "The app's copy is removed once it's on YouTube")
        else InfoRow(Icons.Outlined.Smartphone, if (it.onPhone) "On this phone" else "Not on this phone",
            if (!it.onPhone && it.tgMsg != null) "Opens from Telegram" else if (!it.onPhone && it.ytId != null) "Plays from YouTube" else null)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String?, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
