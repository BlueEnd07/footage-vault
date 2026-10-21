package com.footagevault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.footagevault.app.data.Item
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Change date": pick a day, then keep each video's own time of day (keeps their order) or set one time.
 * E.g. WhatsApp clips received on the 19th that belong with the trip on the 17th.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeDateDialog(items: List<Item>, onDone: (LocalDate, LocalTime?) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val first = items.minByOrNull { it.recorded } ?: return onDismiss()
    val startDate = Instant.ofEpochMilli(first.recorded).atZone(zone).toLocalDate()
    // DatePicker works in UTC midnight millis.
    val dateState = rememberDatePickerState(initialSelectedDateMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var pickTime by remember { mutableStateOf(false) }

    when {
        picked == null -> DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    picked = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }, enabled = dateState.selectedDateMillis != null) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(dateState, title = {
                Text(if (items.size == 1) "Move video to…" else "Move ${items.size} videos to…",
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp))
            })
        }
        !pickTime -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(picked)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (items.size == 1) "Keep the video's time of day, or set a new time?"
                         else "Keep each video's own time of day (their order stays the same), or give them all one time?")
                    Text("Saved on YouTube too, so it shows on every device.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = { onDone(picked!!, null) }) { Text(if (items.size == 1) "Keep its time" else "Keep their times") }
                    TextButton(onClick = { pickTime = true }) { Text("Set a time…") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
        else -> {
            val t0 = Instant.ofEpochMilli(first.recorded).atZone(zone)
            val timeState = rememberTimePickerState(initialHour = t0.hour, initialMinute = t0.minute)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Time") },
                text = { TimePicker(timeState) },
                confirmButton = { TextButton(onClick = { onDone(picked!!, LocalTime.of(timeState.hour, timeState.minute)) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}
