package com.footagevault.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.footagevault.app.data.Item
import com.footagevault.app.net.Auth
import com.footagevault.app.ui.AlbumPicker
import com.footagevault.app.ui.AppViewModel
import com.footagevault.app.ui.ConfirmDialog
import com.footagevault.app.ui.ChangeDateDialog
import com.footagevault.app.ui.DeleteDialog
import com.footagevault.app.ui.DuplicatesScreen
import com.footagevault.app.ui.isDark
import com.footagevault.app.ui.Filter
import com.footagevault.app.ui.LibraryScreen
import com.footagevault.app.ui.MapScreen
import com.footagevault.app.ui.NameDialog
import com.footagevault.app.ui.Onboarding
import com.footagevault.app.ui.SettingsScreen
import com.footagevault.app.ui.VaultTheme
import com.footagevault.app.ui.VideoGrid
import com.footagevault.app.ui.ViewerScreen
import com.footagevault.app.ui.playsInYouTubePlayer
import com.footagevault.app.ui.youTubePlayerIntent
import com.footagevault.app.work.BackupWorker

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startScreen = intent.getStringExtra("screen")
        setContent {
            val theme by vm.theme.collectAsState()
            val dark = isDark(theme)
            // Status / navigation bar icons follow the app's theme, not just the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            VaultTheme(theme) { Surface(color = MaterialTheme.colorScheme.background) { Root(vm, startScreen) } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.prefs.onboarded) vm.rescan()
    }
}

private sealed interface Page {
    data object Videos : Page
    data object Library : Page
    data object Map : Page
    data class Album(val pid: String) : Page
    data class Event(val key: String) : Page
    data class Filtered(val f: Filter) : Page
    data object Duplicates : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: AppViewModel, startScreen: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var onboarded by remember { mutableStateOf(vm.prefs.onboarded) }
    val items by vm.items.collectAsState()
    val albums by vm.albums.collectAsState()
    val events by vm.events.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val busy by vm.busy.collectAsState()
    val columns by vm.columns.collectAsState()
    val duplicates by vm.duplicates.collectAsState()
    val update by vm.update.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    LaunchedEffect(Unit) { vm.checkForUpdate() }
    var page by remember { mutableStateOf<Page>(when (startScreen) { "free" -> Page.Filtered(Filter.FREE); "problems" -> Page.Filtered(Filter.PROBLEMS); "uploading" -> Page.Filtered(Filter.UPLOADING); else -> Page.Videos }) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<Item>, Int>?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    var picker by remember { mutableStateOf<List<Item>?>(null) }
    var deleting by remember { mutableStateOf<List<Item>?>(null) }  // delete dialog
    var viewerRemoved by remember { mutableStateOf(emptySet<Long>()) }  // deleted while the viewer is open
    var redating by remember { mutableStateOf<List<Item>?>(null) }  // change date dialog
    var dialog by remember { mutableStateOf<String?>(null) }  // "newAlbum" | "rename" | "deleteAlbum" | "saveEvent"
    val snack = remember { SnackbarHostState() }

    // System prompts: Google consent, and Android's "delete these videos?" confirmation
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && Auth.tokenFromIntent(ctx, r.data) != null) vm.onSignedIn()
        else vm.say("Sign-in was cancelled")
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onDeleteResult(r.resultCode == Activity.RESULT_OK)
    }
    // Tapping a YouTube video starts it right away in the player window. If the user swipes onto a
    // video that plays from the phone, the gallery viewer takes over at that position.
    var playerList by remember { mutableStateOf<List<Item>>(emptyList()) }
    val playerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val i = r.data?.getIntExtra(YouTubePlayerActivity.EXTRA_INDEX, -1) ?: -1
        if (r.data?.getBooleanExtra(YouTubePlayerActivity.EXTRA_HANDOFF, false) == true && i in playerList.indices) viewer = playerList to i
    }
    fun open(list: List<Item>, index: Int) {
        val item = list.getOrNull(index) ?: return
        if (item.playsInYouTubePlayer()) { playerList = list; playerLauncher.launch(youTubePlayerIntent(ctx, list, index)) }
        else viewer = list to index
    }
    LaunchedEffect(Unit) {
        vm.intents.collect { si ->
            val req = IntentSenderRequest.Builder(si.pi.intentSender).build()
            if (si.kind == "auth") authLauncher.launch(req) else deleteLauncher.launch(req)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    if (!onboarded) { Onboarding(vm) { onboarded = true }; return }
    if (showSettings) { SettingsScreen(vm) { showSettings = false }; return }

    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting || page !is Page.Videos) {
        when {
            selecting -> selected.clear()
            page is Page.Album || page is Page.Event || page is Page.Filtered || page is Page.Duplicates -> page = Page.Library
            else -> page = Page.Videos
        }
    }
    val tab = when (page) { Page.Videos -> 0; Page.Map -> 2; else -> 1 }
    fun shownFor(p: Page): List<Item> = when (p) {
        is Page.Album -> albums.firstOrNull { it.playlistId == p.pid }?.ytIds?.toSet().orEmpty().let { ids -> items.filter { it.ytId in ids } }
        is Page.Event -> events.firstOrNull { it.key == p.key }?.items.orEmpty().sortedByDescending { it.recorded }
        is Page.Filtered -> items.filter(p.f::test)
        else -> items
    }
    val shown: List<Item> = shownFor(page)
    fun toggle(it: Item) { if (it.id in selected) selected.remove(it.id) else selected.add(it.id) }
    val selItems = items.filter { it.id in selected }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { Crossfade(targetState = selecting, animationSpec = tween(180), label = "topbar") { selecting ->
            if (selecting) TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Filled.Close, "Cancel") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    val allFav = selItems.all { it.favorite }
                    IconButton(onClick = { vm.setFavorite(selected.toList(), !allFav); selected.clear() }) { Icon(Icons.Outlined.FavoriteBorder, "Favourite") }
                    IconButton(onClick = { vm.share(selItems); selected.clear() }) { Icon(Icons.Outlined.Share, "Share") }
                    IconButton(onClick = { picker = selItems }) { Icon(Icons.Outlined.LibraryAdd, "Add to album") }
                    IconButton(onClick = { redating = selItems }) { Icon(Icons.Outlined.EditCalendar, "Change date") }
                    IconButton(onClick = { deleting = selItems }) { Icon(Icons.Outlined.Delete, "Delete") }
                },
            )
            else TopAppBar(
                title = {
                    when (val p = page) {
                        is Page.Album, is Page.Event, is Page.Filtered, is Page.Duplicates -> Text(when (p) {
                            is Page.Album -> albums.firstOrNull { it.playlistId == p.pid }?.name ?: "Album"
                            is Page.Event -> events.firstOrNull { it.key == p.key }?.name ?: "Event"
                            is Page.Filtered -> p.f.title
                            is Page.Duplicates -> "Duplicates"
                            else -> ""
                        }, maxLines = 1)
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Footage ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Vault", fontWeight = FontWeight.Medium)
                        }
                    }
                },
                navigationIcon = {
                    if (page is Page.Album || page is Page.Event || page is Page.Filtered || page is Page.Duplicates)
                        IconButton(onClick = { page = Page.Library }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    StatusPill(items, signedIn, vm, onSignIn = vm::signIn, onOpen = { page = Page.Filtered(Filter.UPLOADING) })
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        } },
        bottomBar = {
            AnimatedVisibility(visible = !selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { NavigationBar {
                NavItem(tab == 0, Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, "Videos") { page = Page.Videos }
                NavItem(tab == 1, Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, "Library") { page = Page.Library }
                NavItem(tab == 2, Icons.Filled.Map, Icons.Outlined.Map, "Map") { page = Page.Map }
            } }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // Moving between screens: the new one fades and settles in, the old one fades out quickly.
            AnimatedContent(targetState = page, label = "page", transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.98f)) togetherWith fadeOut(tween(90))
            }) { p ->
            val shown = if (p == page) shown else shownFor(p)
            when (p) {
                Page.Library -> LibraryScreen(items, albums, events,
                    onFilter = { page = Page.Filtered(it) }, onAlbum = { page = Page.Album(it) },
                    onEvent = { page = Page.Event(it) }, onNewAlbum = { dialog = "newAlbum" },
                    duplicates = duplicates.size, onDuplicates = { page = Page.Duplicates })
                Page.Duplicates -> DuplicatesScreen(duplicates, vm) { list, i -> open(list, i) }
                Page.Map -> MapScreen(items) { it, list -> open(list, list.indexOf(it)) }
                else -> PullToRefreshBox(isRefreshing = busy, onRefresh = vm::refresh) {
                    VideoGrid(
                        shown, selected.toSet(), selecting, columns, vm::setColumns,
                        onOpen = { open(shown, shown.indexOf(it)) }, onToggle = ::toggle,
                        onToggleDay = { day -> if (day.all { it.id in selected }) selected.removeAll(day.map { it.id }.toSet()) else day.forEach { if (it.id !in selected) selected.add(it.id) } },
                        header = {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.animateContentSize()) {
                                    update?.let { u ->
                                        val prog = updateProgress
                                        Notice(Icons.Outlined.SystemUpdate, "Update available: ${u.name}",
                                            if (prog != null) "Downloading… ${(prog * 100).toInt()}%" else u.notes.lines().firstOrNull { it.isNotBlank() } ?: "Tap Update to install it.",
                                            if (prog != null) "…" else "Update") { if (prog == null) vm.installUpdate() }
                                    }
                                    if (!signedIn) Notice(Icons.Outlined.CloudOff, "Sign in to start backing up", "Your videos stay on the phone until then.", "Sign in", vm::signIn)
                                    if (p is Page.Videos || (p is Page.Filtered && p.f == Filter.FREE)) {
                                        val free = vm.freeCandidates()
                                        if (free.isNotEmpty()) Notice(Icons.Outlined.DeleteSweep, "Free up ${BackupWorker.fmtSize(free.sumOf { it.size })}",
                                            com.footagevault.app.data.safelyBackedUp(free), "Free up space") { vm.freeUpSpace() }
                                    }
                                    if (p is Page.Filtered && p.f == Filter.UPLOADING && shown.isNotEmpty()) {
                                        val (title, why) = vm.uploadState(items)
                                        Notice(Icons.Outlined.CloudUpload, title, why, "Back up now") { vm.backUpNow() }
                                    }
                                    when (p) {
                                        is Page.Album -> Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { vm.shareAlbum(p.pid) }) {
                                                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
                                            }
                                            FilledTonalButton(onClick = { dialog = "rename" }) { Text("Rename") }
                                            TextButton(onClick = { dialog = "deleteAlbum" }) { Text("Delete album") }
                                        }
                                        is Page.Event -> Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                            Button(onClick = { dialog = "saveEvent" }) { Text("Save as album") }
                                        }
                                        else -> {}
                                    }
                                    if (shown.isEmpty()) Text(
                                        when (p) { is Page.Filtered -> p.f.empty; is Page.Album -> "Select videos and tap Add to album."; else -> "No videos yet." },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            }
        }
    }

    viewer?.let { (list, start) ->
        if (list.isEmpty()) { viewer = null; return@let }
        ViewerScreen(list, start, vm, onClose = { viewer = null; viewerRemoved = emptySet() }, onAddToAlbum = { picker = listOf(it) },
            onDelete = { deleting = listOf(it) }, removed = viewerRemoved)
    }
    deleting?.let { list ->
        DeleteDialog(list, vm, onDeleted = { if (viewer != null) viewerRemoved = viewerRemoved + it.map { x -> x.id } }) { deleting = null; selected.clear() }
    }
    redating?.let { list ->
        ChangeDateDialog(list, onDone = { d, t -> vm.setDate(list, d, t); redating = null; selected.clear() }, onDismiss = { redating = null })
    }
    picker?.let { list ->
        AlbumPicker(albums, list,
            onPick = { pid -> vm.addToAlbum(pid, list); picker = null; selected.clear() },
            onNew = { name -> vm.createAlbum(name, list); picker = null; selected.clear() },
            onDismiss = { picker = null })
    }
    // Sharing an album: a private playlist must become unlisted for others to open the link.
    val share by vm.shareCheck.collectAsState()
    share?.let { c ->
        val lines = buildList {
            if (c.playlistPrivate) add("This album is a private playlist. To share it, it becomes unlisted: anyone with the link can see it, but it isn't searchable on YouTube.")
            if (c.privateVideos > 0) add("${c.privateVideos} video${if (c.privateVideos == 1) " in it is" else "s in it are"} private and won't play for others. Make them unlisted in YouTube Studio if you want them seen.")
        }
        ConfirmDialog(if (c.playlistPrivate) "Share \"${c.name}\"?" else "Share album", lines.joinToString("\n\n"),
            if (c.playlistPrivate) "Make unlisted & share" else "Share", onConfirm = vm::confirmShareAlbum, onDismiss = vm::dismissShare)
    }
    when (dialog) {
        "newAlbum" -> NameDialog("New album", confirm = "Create", onDone = { vm.createAlbum(it); dialog = null }) { dialog = null }
        "rename" -> (page as? Page.Album)?.let { p ->
            NameDialog("Rename album", albums.firstOrNull { it.playlistId == p.pid }?.name.orEmpty(), onDone = { vm.renameAlbum(p.pid, it); dialog = null }) { dialog = null }
        }
        "deleteAlbum" -> (page as? Page.Album)?.let { p ->
            ConfirmDialog("Delete album?", "Its YouTube playlist is deleted too. The videos stay.", "Delete",
                onConfirm = { vm.deleteAlbum(p.pid); dialog = null; page = Page.Library }) { dialog = null }
        }
        "saveEvent" -> (page as? Page.Event)?.let { p ->
            val e = events.firstOrNull { it.key == p.key }
            NameDialog("Save as album", e?.name.orEmpty(), onDone = { vm.createAlbum(it, e?.items.orEmpty()); dialog = null }) { dialog = null }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(sel: Boolean, on: ImageVector, off: ImageVector, label: String, onClick: () -> Unit) =
    NavigationBarItem(selected = sel, onClick = onClick, icon = { Icon(if (sel) on else off, null) }, label = { Text(label) })

@Composable
private fun Notice(icon: ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { FilledTonalButton(onClick = onAction) { Text(action) } }
        }
    }
}

/** Backup state, like Google Photos' avatar ring: backed up / N left / waiting for Wi-Fi / paused / problems. */
@Composable
private fun StatusPill(items: List<Item>, signedIn: Boolean, vm: AppViewModel, onSignIn: () -> Unit, onOpen: () -> Unit) {
    val pending = items.count { it.isPending }
    val failed = items.count { it.status == "failed" }
    val up = items.firstOrNull { it.status == "uploading" }
    val (icon, text, action) = when {
        !signedIn -> Triple(Icons.Outlined.CloudOff, "Sign in", onSignIn)
        vm.prefs.userPaused && pending > 0 -> Triple(Icons.Outlined.PauseCircle, "Paused", onOpen)
        System.currentTimeMillis() < vm.prefs.pausedUntil && pending > 0 -> Triple(Icons.Outlined.PauseCircle, "Daily limit", onOpen)
        up != null -> Triple(null, "$pending left", onOpen)
        pending > 0 -> Triple(if (vm.prefs.wifiOnly) Icons.Outlined.Wifi else Icons.Outlined.CloudOff, "$pending waiting", onOpen)
        failed > 0 -> Triple(Icons.Outlined.ErrorOutline, "$failed problem${if (failed == 1) "" else "s"}", onOpen)
        else -> Triple(Icons.Outlined.CloudDone, "Backed up", onOpen)
    }
    Surface(onClick = action, shape = RoundedCornerShape(18.dp), modifier = Modifier.animateContentSize(),
        color = if (up != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) CircularProgressIndicator(progress = { up?.progress?.coerceAtLeast(.05f) ?: 0f }, modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp)
            else Icon(icon, null, Modifier.size(18.dp), tint = if (text == "Backed up") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
