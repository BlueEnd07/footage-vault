package com.footagevault.app.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.footagevault.app.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Brightness / contrast / saturation / warmth, as one colour matrix (same maths for the preview and the saved file). */
private data class Look(val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val warmth: Float = 0f) {
    fun matrix(): FloatArray {
        val m = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
        val t = brightness * 255f + 128f * (1f - contrast)
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        val w = warmth * 22f
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, w,
            0f, 1f, 0f, 0f, w * 0.25f,
            0f, 0f, 1f, 0f, -w,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m.array
    }
    val filter get() = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix()))
}

private val PRESETS = listOf(
    "Original" to Look(),
    "Vivid" to Look(contrast = 1.1f, saturation = 1.35f),
    "Warm" to Look(saturation = 1.1f, warmth = 0.6f),
    "Cool" to Look(warmth = -0.6f),
    "B&W" to Look(contrast = 1.15f, saturation = 0f),
    "Fade" to Look(brightness = 0.06f, contrast = 0.85f, saturation = 0.8f),
    "Punch" to Look(contrast = 1.25f, saturation = 1.2f),
)

/** Crop shapes: width / height, or null = free, 0 = the photo's own shape. */
private val ASPECTS = listOf("Free" to null, "Original" to 0f, "1:1" to 1f, "4:5" to 0.8f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

/**
 * Edits a copy of a photo: crop, rotate, adjust and filters. "Save copy" writes a new photo next to the original
 * (same date and place, in the Camera folder so it's backed up too). The original is never changed.
 */
@Composable
fun EditorScreen(item: Item, source: Uri, onClose: () -> Unit, onSaved: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) }          // preview-sized, upright
    var rotation by remember { mutableIntStateOf(0) }               // 0, 90, 180, 270
    var look by remember { mutableStateOf(Look()) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }   // fractions of the rotated photo
    var aspect by remember { mutableStateOf<Float?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        base = runCatching { withContext(Dispatchers.IO) { decode(ctx, source, 2048) } }.onFailure { error = "Couldn't open this photo" }.getOrNull()
    }
    val shown: ImageBitmap? = remember(base, rotation) { base?.let { rotate(it, rotation).asImageBitmap() } }
    val ratio = shown?.let { it.width.toFloat() / it.height } ?: 1f

    /** The largest crop with this shape, centred. */
    fun applyAspect(a: Float?) {
        aspect = a
        val want = when (a) { null -> return; 0f -> ratio; else -> a }
        // In fractions: width/height of the photo is `ratio`, so a crop of w×h fractions has shape w*ratio/h.
        var w = 1f; var h = w * ratio / want
        if (h > 1f) { h = 1f; w = want / ratio }
        crop = Rect((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
    }

    // After a turn the photo's shape changes: fit the crop again.
    LaunchedEffect(rotation, ratio) { if (aspect != null) applyAspect(aspect) else crop = Rect(0f, 0f, 1f, 1f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = Color.White) }
                Text("Edit", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { rotation = 0; look = Look(); aspect = null; crop = Rect(0f, 0f, 1f, 1f) }) { Text("Reset", color = Color.White) }
                TextButton(enabled = shown != null && !saving, onClick = {
                    saving = true
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { saveCopy(ctx, item, source, rotation, crop, look) } }
                        saving = false
                        if (ok.isSuccess) onSaved() else error = "Couldn't save: ${ok.exceptionOrNull()?.message}"
                    }
                }) { Text("Save copy", color = MaterialTheme.colorScheme.primary) }
            }

            // Photo with the crop frame
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                if (shown == null) { if (error == null) CircularProgressIndicator(color = Color.White) }
                else CropArea(shown, look, crop, aspect, ratio, showFrame = tab == 0) { crop = it }
            }

            // Tools
            TabRow(tab, containerColor = Color.Black, contentColor = Color.White) {
                listOf("Crop", "Adjust", "Filters").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.fillMaxWidth().height(132.dp).navigationBarsPadding()) {
                when (tab) {
                    0 -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ASPECTS) { (label, a) -> Chip(label, aspect == a && (a != null || aspect == null)) { applyAspect(a) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                                Icon(Icons.Outlined.RotateRight, "Rotate", tint = Color.White)
                            }
                            Text("Rotate", color = Color.White)
                        }
                    }
                    1 -> AdjustPane(look) { look = it }
                    else -> LazyRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(PRESETS) { (label, preset) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { look = preset }) {
                                if (shown != null) Image(shown, label, colorFilter = preset.filter, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(68.dp).clip(RoundedCornerShape(10.dp))
                                        .then(if (look == preset) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier))
                                Text(label, color = if (look == preset) MaterialTheme.colorScheme.primary else Color.White,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(.5f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        error?.let { e ->
            Text(e, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(.7f), RoundedCornerShape(12.dp)).padding(16.dp))
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected, onClick, { Text(label) }, colors = FilterChipDefaults.filterChipColors(labelColor = Color.White))

@Composable
private fun AdjustPane(look: Look, onChange: (Look) -> Unit) {
    var which by remember { mutableIntStateOf(0) }
    val names = listOf("Brightness", "Contrast", "Saturation", "Warmth")
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(names.indices.toList()) { i -> Chip(names[i], which == i) { which = i } }
        }
        val (value, range) = when (which) {
            0 -> look.brightness to -0.4f..0.4f
            1 -> look.contrast to 0.5f..1.6f
            2 -> look.saturation to 0f..2f
            else -> look.warmth to -1f..1f
        }
        Slider(value, { v ->
            onChange(when (which) { 0 -> look.copy(brightness = v); 1 -> look.copy(contrast = v); 2 -> look.copy(saturation = v); else -> look.copy(warmth = v) })
        }, valueRange = range, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary))
    }
}

/** The photo, fitted, with a crop frame you drag by its corners (or move from the middle). */
@Composable
private fun CropArea(img: ImageBitmap, look: Look, crop: Rect, aspect: Float?, ratio: Float, showFrame: Boolean, onCrop: (Rect) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val touch = with(LocalDensity.current) { 36.dp.toPx() }
    // Where the fitted photo sits inside the box, in pixels.
    val fit = remember(box, ratio) {
        if (box.width == 0) Rect.Zero else {
            val bw = box.width.toFloat(); val bh = box.height.toFloat()
            val w = min(bw, bh * ratio); val h = w / ratio
            Rect(Offset((bw - w) / 2f, (bh - h) / 2f), Size(w, h))
        }
    }
    var current by remember { mutableStateOf(crop) }
    current = crop
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }) {
        Image(img, null, colorFilter = look.filter, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (showFrame && fit != Rect.Zero) Canvas(Modifier.fillMaxSize().pointerInput(fit, aspect) {
            var corner = -1  // 0 tl, 1 tr, 2 bl, 3 br, 4 move
            detectDragGestures(
                onDragStart = { p ->
                    val r = toPx(current, fit)
                    val corners = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)
                    corner = corners.indexOfFirst { (it - p).getDistance() < touch }.takeIf { it >= 0 } ?: if (r.contains(p)) 4 else -1
                },
                onDrag = { ch, d ->
                    ch.consume()
                    if (corner < 0) return@detectDragGestures
                    val dx = d.x / fit.width; val dy = d.y / fit.height
                    val c = current
                    val minS = 0.08f
                    var r = when (corner) {
                        4 -> {
                            val mx = dx.coerceIn(-c.left, 1f - c.right); val my = dy.coerceIn(-c.top, 1f - c.bottom)
                            Rect(c.left + mx, c.top + my, c.right + mx, c.bottom + my)
                        }
                        0 -> Rect(min(c.left + dx, c.right - minS), min(c.top + dy, c.bottom - minS), c.right, c.bottom)
                        1 -> Rect(c.left, min(c.top + dy, c.bottom - minS), max(c.right + dx, c.left + minS), c.bottom)
                        2 -> Rect(min(c.left + dx, c.right - minS), c.top, c.right, max(c.bottom + dy, c.top + minS))
                        else -> Rect(c.left, c.top, max(c.right + dx, c.left + minS), max(c.bottom + dy, c.top + minS))
                    }
                    if (aspect != null && corner != 4) {
                        // Keep the shape: height follows width.
                        val want = if (aspect == 0f) ratio else aspect
                        val h = r.width * ratio / want
                        r = if (corner <= 1) Rect(r.left, r.bottom - h, r.right, r.bottom) else Rect(r.left, r.top, r.right, r.top + h)
                    }
                    r = Rect(r.left.coerceAtLeast(0f), r.top.coerceAtLeast(0f), r.right.coerceAtMost(1f), r.bottom.coerceAtMost(1f))
                    if (r.width >= minS && r.height >= minS && (aspect == null || corner == 4 || abs(r.height - r.width * ratio / (if (aspect == 0f) ratio else aspect)) < 0.01f)) {
                        current = r; onCrop(r)
                    }
                },
            )
        }) {
            val r = toPx(current, fit)
            // Dim what's cut away
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(fit); addRect(r)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.55f))
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
            // Thirds
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f; val y = r.top + r.height * i / 3f
                drawLine(Color.White.copy(.35f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(.35f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
            }
            for (p in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) drawCircle(Color.White, 7.dp.toPx(), p)
        }
    }
}

private fun toPx(c: Rect, fit: Rect) = Rect(fit.left + c.left * fit.width, fit.top + c.top * fit.height,
    fit.left + c.right * fit.width, fit.top + c.bottom * fit.height)

private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = max(info.size.width, info.size.height)
        if (s > maxSide) d.setTargetSampleSize((s + maxSide - 1) / maxSide)
    }

private fun rotate(b: Bitmap, deg: Int): Bitmap =
    if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)

/** Full-quality copy: rotate, crop, colour, then save as a new JPEG in DCIM/Camera with the original's date and place. */
private fun saveCopy(ctx: Context, item: Item, source: Uri, rotation: Int, crop: Rect, look: Look) {
    val full = rotate(decode(ctx, source, 6000), rotation)
    val x = (crop.left * full.width).toInt().coerceIn(0, full.width - 1)
    val y = (crop.top * full.height).toInt().coerceIn(0, full.height - 1)
    val w = (crop.width * full.width).toInt().coerceIn(1, full.width - x)
    val h = (crop.height * full.height).toInt().coerceIn(1, full.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(full, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(look.matrix()) })

    val stem = item.name.substringBeforeLast('.')
    val name = "${stem}_edit${System.currentTimeMillis() % 100000}.jpg"
    val cr = ctx.contentResolver
    val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        put(MediaStore.MediaColumns.DATE_TAKEN, item.recorded)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }) ?: throw IllegalStateException("No space for a new photo")
    try {
        cr.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        // Same date and place as the original, so it sits next to it in the timeline and on the map.
        runCatching {
            cr.openFileDescriptor(uri, "rw")!!.use { fd ->
                val exif = android.media.ExifInterface(fd.fileDescriptor)
                val t = java.time.Instant.ofEpochMilli(item.recorded).atZone(java.time.ZoneId.systemDefault())
                exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(t))
                if (item.lat != null && item.lon != null) {
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, dms(item.lat))
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, if (item.lat >= 0) "N" else "S")
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, dms(item.lon))
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, if (item.lon >= 0) "E" else "W")
                }
                exif.saveAttributes()
            }
        }
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        cr.delete(uri, null, null)
        throw e
    }
}

/** EXIF GPS form: "deg/1,min/1,sec*1000/1000". */
private fun dms(v: Double): String {
    val a = abs(v); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val sec = ((a - d) * 60 - m) * 60
    return "$d/1,$m/1,${(sec * 1000).toInt()}/1000"
}

/** The photo to edit: the phone's file, or the original fetched back from Telegram. */
suspend fun editSource(ctx: Context, item: Item): Uri = when {
    item.onPhone && item.uri != null -> Uri.parse(item.uri)
    item.tgMsg != null -> Uri.fromFile(File(com.footagevault.app.net.Telegram.download(ctx, item) {}))
    else -> throw IllegalStateException("This photo isn't available")
}
