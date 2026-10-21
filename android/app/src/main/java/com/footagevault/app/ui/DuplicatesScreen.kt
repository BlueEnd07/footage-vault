package com.footagevault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.footagevault.app.data.Item
import com.footagevault.app.work.BackupWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sets of copies of the same video, side by side; delete the extra copies. */
@Composable
fun DuplicatesScreen(groups: List<List<Item>>, vm: AppViewModel, onOpen: (List<Item>, Int) -> Unit) {
    var confirm by remember { mutableStateOf<Pair<Item, String>?>(null) }  // item, "youtube" | "phone"
    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp))
            Text("No duplicates found", style = MaterialTheme.typography.titleMedium)
            Text("Videos with the same length and the same name, recording time or size show up here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${groups.size} set${if (groups.size == 1) "" else "s"} of copies. Keep one of each and delete the rest.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
        items(groups, key = { g -> g.joinToString(",") { it.id.toString() } }) { group ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(group, key = { it.id }) { it -> Copy(it, onOpen = { onOpen(group, group.indexOf(it)) }) { kind -> confirm = it to kind } }
                }
            }
        }
    }
    confirm?.let { (item, kind) ->
        ConfirmDialog(
            title = if (kind == "youtube") "Delete from YouTube?" else "Delete from phone?",
            text = if (kind == "youtube") "This copy is deleted from your channel permanently. The other copies stay."
                   else if (item.ytId != null) "The copy on YouTube stays." else "This copy isn't on YouTube. The other copies stay.",
            confirm = "Delete",
            onConfirm = { if (kind == "youtube") vm.deleteFromYouTube(item) else vm.removeFromPhone(listOf(item)); confirm = null },
        ) { confirm = null }
    }
}

@Composable
private fun Copy(it: Item, onOpen: () -> Unit, onDelete: (String) -> Unit) {
    val t = Instant.ofEpochMilli(it.recorded).atZone(ZoneId.systemDefault())
    Column(Modifier.width(150.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Surface(onClick = onOpen, color = androidx.compose.ui.graphics.Color.Transparent) {
                AsyncImage(it.thumbModel, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        val where = when {
            it.onPhone && it.ytId != null -> "Phone + YouTube"
            it.onPhone -> "Only on phone"
            else -> "YouTube" + (it.privacy?.let { p -> " · $p" } ?: "")
        }
        Text(where, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        Text(it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(listOfNotNull(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault()).format(t),
            fmtDur(it.durationMs), if (it.size > 0) BackupWorker.fmtSize(it.size) else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (it.ytId != null) OutlinedButton(onClick = { onDelete("youtube") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete on YouTube") }
            if (it.onPhone) OutlinedButton(onClick = { onDelete("phone") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete on phone") }
        }
    }
}
