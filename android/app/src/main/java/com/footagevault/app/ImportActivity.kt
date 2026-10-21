package com.footagevault.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footagevault.app.data.Db
import com.footagevault.app.data.Importer
import com.footagevault.app.data.Prefs
import com.footagevault.app.ui.VaultTheme
import com.footagevault.app.ui.isDark
import com.footagevault.app.work.BackupWorker
import com.footagevault.app.work.ImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Share → Footage Vault" from Google Photos (or any app): pick an album name, copy the videos in,
 * and they upload to YouTube in the background like phone videos.
 */
class ImportActivity : ComponentActivity() {
    private val vm: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.load(Importer.sharedUris(intent))
        val prefs = Prefs(this)
        setContent {
            val dark = isDark(prefs.theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            VaultTheme(prefs.theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    ImportScreen(vm, signedIn = prefs.signedIn, onClose = ::finish, onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", "uploading")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    })
                }
            }
        }
    }
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    sealed interface State {
        data object Reading : State
        data class Ready(val videos: List<Importer.Source>, val skipped: Int, val albums: List<String>, val freeBytes: Long) : State
        /** Handed to the background copier (ImportService). */
        data class Started(val count: Int, val album: String?) : State
    }
    private val _state = MutableStateFlow<State>(State.Reading)
    val state = _state.asStateFlow()

    fun load(uris: List<Uri>) = viewModelScope.launch(Dispatchers.IO) {
        val (videos, skipped) = Importer.describe(ctx, uris)
        val albums = Db.get(ctx).albums().map { it.name }.sorted()
        _state.value = State.Ready(videos, skipped, albums, Importer.dir(ctx).usableSpace)
    }

    fun start(album: String?) {
        val ready = _state.value as? State.Ready ?: return
        ImportService.start(ctx, ready.videos, album)
        _state.value = State.Started(ready.videos.size, album?.trim()?.ifBlank { null })
    }
}

@Composable
private fun ImportScreen(vm: ImportViewModel, signedIn: Boolean, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text("Back up to YouTube", style = MaterialTheme.typography.headlineSmall)
        }
        when (val s = state) {
            ImportViewModel.State.Reading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ImportViewModel.State.Ready -> ReadyPane(s, signedIn, onClose, vm::start)
            is ImportViewModel.State.Started -> {
                Text("Copying ${s.count} video${if (s.count == 1) "" else "s"} in the background", style = MaterialTheme.typography.titleMedium)
                val lines = buildList {
                    add("You can close this. Progress is in your notifications.")
                    add("All videos are downloaded first, a few at a time. Then they upload to YouTube with their date, place and name" +
                        (s.album?.let { " and go into the album \"$it\"" } ?: "") + ".")
                    if (!signedIn) add("Sign in in the app to start uploading them.")
                    add("In your timeline they're on the days they were filmed. See uploads shows how it's going.")
                }
                lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenApp) { Text("See uploads") }
                    TextButton(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ReadyPane(s: ImportViewModel.State.Ready, signedIn: Boolean, onClose: () -> Unit, onStart: (String?) -> Unit) {
    var album by remember { mutableStateOf("") }
    val bytes = s.videos.sumOf { it.size.coerceAtLeast(0) }
    if (s.videos.isEmpty()) {
        Text("No videos to back up" + if (s.skipped > 0) " (photos and GIFs are left out)." else ".")
        TextButton(onClick = onClose) { Text("Close") }
        return
    }
    Text("${s.videos.size} video${if (s.videos.size == 1) "" else "s"} · ${BackupWorker.fmtSize(bytes)}", style = MaterialTheme.typography.titleMedium)
    if (s.skipped > 0) Text("${s.skipped} photo${if (s.skipped == 1) "" else "s"} or GIF${if (s.skipped == 1) "" else "s"} left out",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(album, { album = it }, label = { Text("Album (optional)") }, singleLine = true,
        supportingText = { Text("Becomes a YouTube playlist, like the app's other albums") }, modifier = Modifier.fillMaxWidth())
    if (s.albums.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.albums) { name -> FilterChip(album == name, { album = if (album == name) "" else name }, { Text(name) }) }
    }
    val tooBig = bytes > s.freeBytes - 300_000_000L
    Text(
        if (tooBig) "Not enough space: needs ${BackupWorker.fmtSize(bytes)}, ${BackupWorker.fmtSize(s.freeBytes)} free. Share fewer at a time."
        else "Videos are copied into the app, uploaded with their date, place and name, then the copies are deleted." +
            if (signedIn) "" else " Sign in in the app to start uploading.",
        style = MaterialTheme.typography.bodySmall,
        color = if (tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStart(album) }, enabled = !tooBig) { Text("Back up") }
        TextButton(onClick = onClose) { Text("Cancel") }
    }
}
