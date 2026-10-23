package com.footagevault.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.footagevault.app.net.Telegram

/**
 * Settings → Telegram: connect your own Telegram account (API ID + hash from my.telegram.org, phone, code),
 * then photos and short videos go to a private channel as original files.
 */
@Composable
fun TelegramCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    val state by vm.telegram.collectAsState()
    val busy by vm.tgBusy.collectAsState()
    val hint = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            Telegram.State.NotSetUp -> {
                var apiId by rememberSaveable { mutableStateOf("") }
                var apiHash by rememberSaveable { mutableStateOf("") }
                Text("Photos and short videos go to a private channel in your own Telegram account, as the original files. Long videos stay on YouTube.")
                Text("1. Open my.telegram.org, sign in, tap API development tools and create an app (any name).\n" +
                    "2. Copy the App api_id and App api_hash here.", style = hint, color = muted)
                OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))) }) {
                    Text("Open my.telegram.org")
                }
                OutlinedTextField(apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiHash, { apiHash = it.trim() }, label = { Text("API hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("They stay on this phone.", style = hint, color = muted)
                Button(onClick = { vm.tgKeys(apiId, apiHash) }, enabled = !busy && apiId.isNotBlank() && apiHash.isNotBlank()) { Text("Connect") }
            }
            Telegram.State.Starting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Starting Telegram…")
            }
            Telegram.State.WaitPhone -> {
                var phone by rememberSaveable { mutableStateOf("+91") }
                Text("Your Telegram phone number, with the country code.")
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.tgPhone(phone) }, enabled = !busy && phone.length > 6) { Text("Send code") }
                    TextButton(onClick = vm::tgDisconnect) { Text("Cancel") }
                }
            }
            is Telegram.State.WaitCode -> {
                var code by rememberSaveable { mutableStateOf("") }
                Text("Enter the code Telegram sent to ${s.sentTo}.")
                OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("Code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.tgCode(code) }, enabled = !busy && code.length >= 5) { Text("Sign in") }
                    TextButton(onClick = vm::tgDisconnect) { Text("Start over") }
                }
            }
            is Telegram.State.WaitPassword -> {
                var pw by rememberSaveable { mutableStateOf("") }
                Text("Your account has two-step verification. Enter its password." + if (s.hint.isNotBlank()) " Hint: ${s.hint}" else "")
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.tgPassword(pw) }, enabled = !busy && pw.isNotEmpty()) { Text("Sign in") }
                    TextButton(onClick = vm::tgDisconnect) { Text("Start over") }
                }
            }
            is Telegram.State.Ready -> ReadyPane(vm, s)
            is Telegram.State.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::tgDisconnect) { Text("Start over") }
            }
        }
        if (busy) Text("Working…", style = hint, color = muted)
    }
}

@Composable
private fun ReadyPane(vm: AppViewModel, s: Telegram.State.Ready) {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun photosAllowed() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(photosAllowed()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok; if (ok) vm.tgChanged() }
    // The channel is ready: pick up photos and re-route short videos.
    LaunchedEffect(s.channel) { if (s.channel) vm.tgChanged() }

    Text("Connected as ${s.name}", fontWeight = FontWeight.Medium)
    Text(if (s.channel) "Backing up to your private channel \"${Telegram.CHANNEL_TITLE}\"." else "Setting up your private channel…",
        style = MaterialTheme.typography.bodySmall, color = muted)
    if (!allowed) Button(onClick = { ask.launch(Manifest.permission.READ_MEDIA_IMAGES) }) { Text("Allow photos") }

    var minutes by remember { mutableIntStateOf(vm.prefs.tgShortMinutes) }
    Text("Videos to Telegram", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    Text("Short videos go up as the original file and don't use YouTube's daily limit. Longer ones go to YouTube.",
        style = MaterialTheme.typography.bodySmall, color = muted)
    for ((m, label) in listOf(0 to "None (all videos to YouTube)", 1 to "Up to 1 minute", 3 to "Up to 3 minutes",
        5 to "Up to 5 minutes", 10 to "Up to 10 minutes")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(minutes == m, { minutes = m; vm.setShortMinutes(m) })
            Text(label)
        }
    }
    TextButton(onClick = vm::tgDisconnect) { Text("Disconnect Telegram") }
}
