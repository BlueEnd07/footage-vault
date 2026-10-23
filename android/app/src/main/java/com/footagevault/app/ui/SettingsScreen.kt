package com.footagevault.app.ui

import androidx.compose.animation.animateContentSize
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.footagevault.app.R
import com.footagevault.app.data.MediaScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val p = vm.prefs
    val signedIn by vm.signedIn.collectAsState()
    var tick by remember { mutableIntStateOf(0) }  // re-read prefs after a change
    fun changed() { tick++; vm.settingsChanged() }
    val folders = remember(tick) { MediaScanner.folders(ctx) }
    // Android's photo picker (shows Google Photos videos and albums too, when the phone links it to Google Photos)
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) ctx.startActivity(importIntent(ctx, uris))
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            key(tick) {
                Card(Icons.Outlined.PlayCircle, "YouTube") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                            Text((p.channelTitle ?: "Y").take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp), fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (signedIn) p.channelTitle ?: "YouTube channel" else "Not signed in", fontWeight = FontWeight.Medium)
                            Text(if (signedIn) "Backing up to this channel" else "Sign in to start backing up",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (signedIn) OutlinedButton(onClick = { vm.signOut(); tick++ }) { Text("Sign out") }
                        else Button(onClick = vm::signIn) { Text("Sign in") }
                    }
                }

                val theme by vm.theme.collectAsState()
                Card(Icons.Outlined.DarkMode, "Theme") {
                    for ((k, title, sub) in listOf(
                        Triple("system", "Same as phone", "Follows your phone's dark mode"),
                        Triple("light", "Light", ""), Triple("dark", "Dark", ""),
                        Triple("black", "Pure black", "True black for OLED screens, saves battery"),
                    )) Radio(title, sub, theme == k) { vm.setTheme(k) }
                }

                Card(Icons.Outlined.CloudUpload, "Backup") {
                    Toggle("Only on Wi-Fi", "Never use mobile data for uploads", p.wifiOnly) { p.wifiOnly = it; changed() }
                    Toggle("Only while charging", "Saves battery on big uploads", p.chargingOnly) { p.chargingOnly = it; changed() }
                    Toggle("Pause backup", null, p.userPaused) { p.userPaused = it; changed() }
                    Toggle("Back up older videos too", "Off: only videos recorded after you set up the app", p.backupFrom == 0L) {
                        p.backupFrom = if (it) 0L else System.currentTimeMillis(); changed()
                    }
                    Row(Modifier.padding(top = 8.dp)) { Button(onClick = vm::backUpNow) { Text("Back up now") } }
                }

                Card(Icons.AutoMirrored.Outlined.Send, "Telegram · photos and short videos") { TelegramCard(vm) }

                Card(Icons.Outlined.PhotoLibrary, "Back up from Google Photos") {
                    Text("In Google Photos, open an album, select the photos and videos, tap Share and choose Footage Vault. " +
                        "You can name an album, and everything is backed up with its date, place and name.")
                    Text("Pasting an album link isn't possible: Google stopped letting apps open Google Photos albums in 2025.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val photos = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                            if (photos != null) ctx.startActivity(photos) else vm.say("Google Photos isn't installed")
                        }) { Text("Open Google Photos") }
                        OutlinedButton(onClick = {
                            pickVideos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }) { Text("Choose photos & videos") }
                    }
                }

                Card(Icons.Outlined.Lock, "Privacy of new uploads") {
                    Radio("Private", "Only you can watch them", p.privacy == "private") { p.privacy = "private"; changed() }
                    Radio("Unlisted", "Plays inside this app, but anyone with the link can watch", p.privacy == "unlisted") { p.privacy = "unlisted"; changed() }
                    Text(
                        if (p.forcedPrivate) "YouTube made your last upload private: your Google project hasn't passed YouTube's API audit yet, so it can only upload private videos."
                        else "Unlisted needs your Google project to pass YouTube's free API audit first. Until then YouTube makes uploads private anyway.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    val items by vm.items.collectAsState()
                    val priv = items.count { it.ytId != null && it.privacy == "private" }
                    if (priv > 0) OutlinedButton(onClick = { vm.makeUnlisted(vm.privateVideos()) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Make $priv private video${if (priv == 1) "" else "s"} unlisted")
                    }
                }

                Card(Icons.Outlined.Folder, "Folders to back up") {
                    val chosen = p.folders
                    for (f in folders) {
                        Row(Modifier.fillMaxWidth().clickable {
                            p.folders = if (f.name in chosen) chosen - f.name else chosen + f.name; changed()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.name in chosen, null)
                            Spacer(Modifier.width(8.dp))
                            Text(f.name, Modifier.weight(1f))
                            Text("${f.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (folders.isEmpty()) Text("No videos found on this phone yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val update by vm.update.collectAsState()
            val prog by vm.updateProgress.collectAsState()
            Card(Icons.Outlined.SystemUpdate, "App version") {
                Text("Footage Vault ${com.footagevault.app.net.Updater.installedName(ctx)}", fontWeight = FontWeight.Medium)
                Text(
                    when {
                        prog != null -> "Downloading update… ${((prog ?: 0f) * 100).toInt()}%"
                        update != null -> "${update!!.name} is available."
                        else -> "Updates come from the project's GitHub Releases."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp)) {
                    if (update != null) Button(onClick = vm::installUpdate, enabled = prog == null) { Text("Update") }
                    else OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Check for updates") }
                }
            }
            Text("Footage Vault · videos are backed up to your own YouTube channel. Nothing goes anywhere else.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
    }
}

/** Hands picked photos and videos to the same screen as "Share → Footage Vault". */
private fun importIntent(ctx: android.content.Context, uris: List<android.net.Uri>) =
    android.content.Intent(ctx, com.footagevault.app.ImportActivity::class.java)
        .setAction(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun Card(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Radio(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First run: permissions → Google sign-in → what to back up. */
@Composable
fun Onboarding(vm: AppViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val signedIn by vm.signedIn.collectAsState()
    var granted by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[Manifest.permission.READ_MEDIA_VIDEO] == true
        vm.rescan()
        step = 1
    }
    val existing = remember(granted) { if (granted) MediaScanner.countToBackUp(ctx, vm.prefs.folders, 0L) else 0 }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(88.dp))
        Spacer(Modifier.height(20.dp))
        Text("Footage Vault", style = MaterialTheme.typography.headlineMedium)
        Text("Your videos, backed up to your own YouTube channel. Free up space on your phone, and keep watching here.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 36.dp))
        when {
            step == 0 -> {
                Text("First, allow access to your videos.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    perms.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow access") }
            }
            !signedIn -> {
                Text("Now sign in with the Google account that owns your YouTube channel.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = vm::signIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
            }
            else -> {
                Text("Back up the $existing video${if (existing == 1) "" else "s"} already in your Camera folder?", textAlign = TextAlign.Center)
                Text("Videos already on your channel are recognised and not uploaded again.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.prefs.backupFrom = 0L; vm.prefs.onboarded = true; vm.settingsChanged(); onDone() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back up everything")
                }
                OutlinedButton(onClick = { vm.prefs.backupFrom = System.currentTimeMillis(); vm.prefs.onboarded = true; vm.settingsChanged(); onDone() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Only new videos from now on") }
            }
        }
    }
}
