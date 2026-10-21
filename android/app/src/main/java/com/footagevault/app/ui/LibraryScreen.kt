package com.footagevault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.footagevault.app.data.Db
import com.footagevault.app.data.Item

enum class Filter(val title: String, val empty: String) {
    FAV("Favourites", "Tap ♡ on a video to add it here."),
    UPLOADING("Uploading", "Everything is backed up."),
    FREE("Free up space", "Nothing to remove right now."),
    PROBLEMS("Problems", "No problems."),
    ;
    fun test(it: Item) = when (this) {
        FAV -> it.favorite
        UPLOADING -> it.isPending
        FREE -> it.onPhone && it.status == "backed_up"
        PROBLEMS -> it.status == "failed"
    }
}

@Composable
fun LibraryScreen(
    items: List<Item>, albums: List<Db.Album>, events: List<VaultEvent>,
    onFilter: (Filter) -> Unit, onAlbum: (String) -> Unit, onEvent: (String) -> Unit, onNewAlbum: () -> Unit,
    duplicates: Int = 0, onDuplicates: () -> Unit = {},
) {
    val byYt = remember(items) { items.filter { it.ytId != null }.associateBy { it.ytId } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val utils = listOf(
            Triple(Filter.FAV, Icons.Outlined.FavoriteBorder, items.count(Filter.FAV::test)),
            Triple(Filter.UPLOADING, Icons.Outlined.CloudUpload, items.count(Filter.UPLOADING::test)),
            Triple(Filter.FREE, Icons.Outlined.DeleteSweep, items.count(Filter.FREE::test)),
            Triple(Filter.PROBLEMS, Icons.Outlined.ErrorOutline, items.count(Filter.PROBLEMS::test)),
        )
        items(utils, key = { it.first.name }) { (f, icon, n) -> UtilityTile(f.title, icon, n) { onFilter(f) } }
        item(key = "dupes") { UtilityTile("Duplicates", Icons.Outlined.ContentCopy, duplicates, onDuplicates) }

        if (events.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) { SectionHead("Events", "Clips less than 3 hours apart") }
            items(events, key = { it.key }) { e -> CoverCard(e.cover.thumbModel, e.name, "${e.items.size} clips") { onEvent(e.key) } }
        }
        item(span = { GridItemSpan(2) }) { SectionHead("Albums", "Each album is a private YouTube playlist") }
        item(key = "new") {
            Column(Modifier.clickable(onClick = onNewAlbum)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                }
                Text("New album", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                Text(" ", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(albums, key = { it.playlistId }) { a ->
            val cover = a.ytIds.firstNotNullOfOrNull { byYt[it] }?.thumbModel ?: a.ytIds.firstOrNull()?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            CoverCard(cover, a.name, "${a.ytIds.size} video${if (a.ytIds.size == 1) "" else "s"}") { onAlbum(a.playlistId) }
        }
    }
}

@Composable
private fun UtilityTile(title: String, icon: ImageVector, n: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n > 0) Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionHead(title: String, sub: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoverCard(model: Any?, title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(model, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Add to album" sheet: pick an album, or name a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPicker(albums: List<Db.Album>, items: List<Item>, onPick: (String) -> Unit, onNew: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val single = items.singleOrNull()?.ytId
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(if (items.size > 1) "Add ${items.size} videos to" else "Add to album", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, placeholder = { Text("New album") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (name.isNotBlank()) onNew(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
            }
            LazyColumn(Modifier.height(320.dp)) {
                items(albums, key = { it.playlistId }) { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(a.playlistId) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            a.ytIds.firstOrNull()?.let { AsyncImage("https://i.ytimg.com/vi/$it/default.jpg", null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                ?: Icon(Icons.Outlined.PhotoLibrary, null)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.ytIds.size} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (single != null && single in a.ytIds) Icon(Icons.Filled.CheckCircle, "In album", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Save", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
