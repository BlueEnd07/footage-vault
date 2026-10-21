package com.footagevault.app.ui

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
    return YouTubePlayerActivity.intent(ctx, ids.toTypedArray(), labels.toTypedArray(), index)
}

fun openOnYouTube(ctx: Context, ytId: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$ytId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$ytId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    list: List<Item>, start: Int, vm: AppViewModel,
    onClose: () -> Unit, onAddToAlbum: (Item) -> Unit,
) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) { list.size }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val current = list.getOrNull(pager.currentPage) ?: return onClose()
    // Live version of the current item (favourite / status changes)
    val all by vm.items.collectAsState()
    val live = all.firstOrNull { it.id == current.id } ?: current

    // YouTube player window: swipes there move through this same list; when it closes, follow it here.
    val scope = rememberCoroutineScope()
    val ytLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val i = r.data?.getIntExtra(YouTubePlayerActivity.EXTRA_INDEX, -1) ?: -1
        if (i in list.indices) scope.launch { pager.scrollToPage(i) }
    }
    fun playOnYouTubeHere(page: Int) =
        ytLauncher.launch(youTubePlayerIntent(ctx, list.map { x -> all.firstOrNull { it.id == x.id } ?: x }, page))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { list[it].id }) { page ->
            val item = all.firstOrNull { it.id == list[page].id } ?: list[page]
            val isCurrent = page == pager.currentPage
            val embeddable = item.ytId != null && item.privacy != null && item.privacy != "private"
            when {
                isCurrent && item.onPhone && item.uri != null -> LocalPlayer(item)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(item.thumbModel, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    if (!item.onPhone && item.ytId != null) {
                        if (embeddable) {
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
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
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
                .navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            BarButton(if (live.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favourite",
                if (live.favorite) Color(0xFFF28B82) else Color.White) { vm.setFavorite(listOf(live.id), !live.favorite) }
            BarButton(Icons.Outlined.Info, "Info") { showInfo = true }
            BarButton(Icons.Outlined.LibraryAdd, "Add to") { onAddToAlbum(live) }
            if (live.onPhone && live.status == "backed_up")
                BarButton(Icons.Outlined.DeleteSweep, "Free up") { vm.freeUpSpace(listOf(live)) }
        }
    }

    if (showInfo) ModalBottomSheet(onDismissRequest = { showInfo = false }) { InfoSheet(live) }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = tint)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LocalPlayer(item: Item) {
    val ctx = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(item.id) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize())
}

@Composable
private fun InfoSheet(it: Item) {
    val t = Instant.ofEpochMilli(it.recorded).atZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        InfoRow(Icons.Outlined.CalendarMonth, DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(t),
            DateTimeFormatter.ofPattern("h:mm a · O", Locale.getDefault()).format(t))
        val meta = listOfNotNull(if (it.width > 0) "${it.width} × ${it.height}" else null,
            if (it.size > 0) BackupWorker.fmtSize(it.size) else null, fmtDur(it.durationMs)).joinToString(" · ")
        InfoRow(Icons.Outlined.Movie, it.name, meta)
        if (it.place != null) InfoRow(Icons.Outlined.Place, it.place, "%.4f, %.4f".format(it.lat, it.lon))
        val (icon, text) = when (it.status) {
            "backed_up", "youtube" -> Icons.Outlined.CloudDone to "Backed up to YouTube"
            "failed" -> Icons.Outlined.ErrorOutline to "Backup failed"
            "local" -> Icons.Outlined.Smartphone to "Only on this phone (folder not backed up)"
            "processing" -> Icons.Outlined.CloudUpload to "Uploaded · YouTube is processing"
            "uploading" -> Icons.Outlined.CloudUpload to "Uploading · ${(it.progress * 100).toInt()}%"
            else -> Icons.Outlined.CloudUpload to "Waiting to back up"
        }
        InfoRow(icon, text, listOfNotNull(it.privacy?.let { p -> "YouTube: $p" }, it.error).joinToString(" · ").ifBlank { null })
        InfoRow(Icons.Outlined.Smartphone, if (it.onPhone) "On this phone" else "Not on this phone", if (!it.onPhone && it.ytId != null) "Plays from YouTube" else null)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
