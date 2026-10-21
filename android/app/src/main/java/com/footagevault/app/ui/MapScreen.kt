package com.footagevault.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.footagevault.app.data.Item
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Clips on an OpenStreetMap map (needs the camera's location setting to be on). */
@Composable
fun MapScreen(items: List<Item>, onOpen: (Item, List<Item>) -> Unit) {
    val ctx = LocalContext.current
    val located = remember(items) { items.filter { it.lat != null && it.lon != null } }
    val map = remember { MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(4.0); controller.setCenter(GeoPoint(20.6, 78.9)) } }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize(), update = { mv ->
            mv.overlays.clear()
            for (it in located) {
                mv.overlays += Marker(mv).apply {
                    position = GeoPoint(it.lat!!, it.lon!!)
                    title = it.place ?: it.name
                    setOnMarkerClickListener { _, _ -> onOpen(it, located); true }
                }
            }
            if (located.size > 1) mv.post {
                mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(located.map { GeoPoint(it.lat!!, it.lon!!) }).increaseByScale(1.3f), false)
            } else located.firstOrNull()?.let { mv.controller.setZoom(14.0); mv.controller.setCenter(GeoPoint(it.lat!!, it.lon!!)) }
            mv.invalidate()
        })
        if (located.isEmpty()) Card(Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("No videos with a location yet", fontWeight = FontWeight.Medium)
                Text("Your camera isn't saving location. Open Camera → Settings and turn on Location / Geotag. New videos will show up here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
