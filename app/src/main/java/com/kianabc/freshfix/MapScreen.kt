package com.kianabc.freshfix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Map of where photos were taken, plus downloading the visible area for offline use
 * (map tiles for this screen, addresses for the photo stamp).
 */
@Composable
fun MapScreen(
    gps: GpsTracker,
    downloader: AreaDownloader,
    photoLog: PhotoLog,
    appScope: CoroutineScope,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    val fix by gps.latest.collectAsState()
    val areas by downloader.areas.collectAsState()
    val progress by downloader.progress.collectAsState()
    val message by downloader.message.collectAsState()
    var hint by remember { mutableStateOf<String?>(null) }
    var showAreas by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val photos = remember { photoLog.points() }

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(AreaDownloader.STYLE_URL)) { s ->
                addOverlays(s, photos)
                style = s
                val start = gps.latest.value
                when {
                    start != null -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.latitude, start.longitude), 15.0))
                    photos.size > 1 -> m.moveCamera(
                        CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.Builder().includes(photos.map { LatLng(it.lat, it.lon) }).build(), 80
                        )
                    )
                    photos.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(photos[0].lat, photos[0].lon), 15.0))
                    else -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.5, -98.35), 3.0))
                }
            }
        }
    }

    LaunchedEffect(style, fix) {
        val f = fix ?: return@LaunchedEffect
        style?.getSourceAs<GeoJsonSource>(SOURCE_ME)?.setGeoJson(pointFeature(f.latitude, f.longitude, "{}"))
    }
    LaunchedEffect(style, areas) {
        style?.getSourceAs<GeoJsonSource>(SOURCE_AREAS)?.setGeoJson(areasGeoJson(areas))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onBack) { Text("← Camera") }
                OutlinedButton(
                    onClick = {
                        val f = fix ?: return@OutlinedButton
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.latitude, f.longitude), 16.0))
                    },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                ) { Text("My location", color = Color.Black) }
            }

            Spacer(Modifier.weight(1f))

            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xEE111111), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                val p = progress
                if (p != null) {
                    Text("Downloading ${p.name}", color = Color.White, fontWeight = FontWeight.Bold)
                    val fraction = if (p.tilesRequired > 0) p.tilesDone.toFloat() / p.tilesRequired else 0f
                    Text(
                        "Map: " + if (p.mapDone) "done" else "${p.tilesDone} / ${p.tilesRequired} pieces",
                        color = Color.White, fontSize = 13.sp,
                    )
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(
                        "Addresses: " + if (p.addressesDone) "done (${p.addressCount})" else "${p.addressCount} so far…",
                        color = Color.White, fontSize = 13.sp,
                    )
                    p.note?.let { Text(it, color = Color(0xFFFFCC80), fontSize = 12.sp) }
                    TextButton(onClick = downloader::cancel) { Text("Cancel") }
                } else {
                    Text(
                        "Pan and zoom to the area you'll be in, then download it. " +
                            "Photos taken there get a street address even with no signal.",
                        color = Color.White, fontSize = 13.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            downloader.clearMessage()
                            val visible = map?.projection?.visibleRegion?.latLngBounds ?: return@Button
                            val bounds = Bounds(
                                north = visible.northEast.latitude, south = visible.southWest.latitude,
                                east = visible.northEast.longitude, west = visible.southWest.longitude,
                            )
                            hint = if (bounds.widthKm > AreaDownloader.MAX_SIDE_KM || bounds.heightKm > AreaDownloader.MAX_SIDE_KM) {
                                "Zoom in: this view is %.0f × %.0f km, max is %.0f × %.0f km per download".format(
                                    bounds.widthKm, bounds.heightKm, AreaDownloader.MAX_SIDE_KM, AreaDownloader.MAX_SIDE_KM
                                )
                            } else {
                                val name = "Area " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                                downloader.start(appScope, name, bounds)
                                null
                            }
                        },
                    ) { Text("Download this area for offline") }
                }

                (hint ?: message)?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(it, color = Color(0xFFFFCC80), fontSize = 13.sp)
                }

                Spacer(Modifier.size(6.dp))
                TextButton(onClick = { showAreas = !showAreas }) {
                    Text(
                        (if (showAreas) "▾ " else "▸ ") + "Offline areas (${areas.size})",
                        color = Color.White,
                    )
                }
                if (showAreas) {
                    if (areas.isEmpty()) {
                        Text("None yet. Downloaded areas show as blue boxes on the map.", color = Color.Gray, fontSize = 13.sp)
                    }
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(areas, key = { it.id }) { area ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(area.name, color = Color.White, fontSize = 14.sp)
                                    Text(
                                        "%.1f × %.1f km · %d addresses · %d streets".format(
                                            area.bounds.widthKm, area.bounds.heightKm, area.addressCount, area.streetCount
                                        ),
                                        color = Color.Gray, fontSize = 12.sp,
                                    )
                                }
                                TextButton(onClick = {
                                    val b = area.bounds
                                    map?.moveCamera(
                                        CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), 60)
                                    )
                                }) { Text("Show") }
                                TextButton(onClick = { scope.launch { downloader.delete(area) } }) {
                                    Text("Delete", color = Color(0xFFEF9A9A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SOURCE_ME = "freshfix-me"
private const val SOURCE_AREAS = "freshfix-areas"
private const val SOURCE_PHOTOS = "freshfix-photos"

private fun addOverlays(style: Style, photos: List<PhotoPoint>) {
    style.addSource(GeoJsonSource(SOURCE_AREAS, areasGeoJson(emptyList())))
    style.addLayer(FillLayer("freshfix-areas-fill", SOURCE_AREAS).withProperties(fillColor("#1565C0"), fillOpacity(0.06f)))
    style.addLayer(LineLayer("freshfix-areas-line", SOURCE_AREAS).withProperties(lineColor("#1565C0"), lineWidth(2f)))

    val photoFeatures = photos.joinToString(",") {
        pointFeature(it.lat, it.lon, """{"status":"${it.status}"}""")
    }
    style.addSource(GeoJsonSource(SOURCE_PHOTOS, """{"type":"FeatureCollection","features":[$photoFeatures]}"""))
    style.addLayer(
        CircleLayer("freshfix-photos", SOURCE_PHOTOS).withProperties(
            circleRadius(6f),
            circleColor(
                Expression.match(
                    Expression.get("status"), Expression.color(0xFFF9A825.toInt()),
                    Expression.stop("OK", Expression.color(0xFF2E7D32.toInt())),
                    Expression.stop("NO_FIX", Expression.color(0xFFC62828.toInt())),
                )
            ),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(1.5f),
        )
    )

    style.addSource(GeoJsonSource(SOURCE_ME, """{"type":"FeatureCollection","features":[]}"""))
    style.addLayer(
        CircleLayer("freshfix-me", SOURCE_ME).withProperties(
            circleRadius(8f), circleColor("#1E88E5"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
        )
    )
}

private fun pointFeature(lat: Double, lon: Double, properties: String) =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":$properties}"""

private fun areasGeoJson(areas: List<OfflineArea>): String {
    val features = areas.joinToString(",") { a ->
        val b = a.bounds
        val ring = "[[${b.west},${b.south}],[${b.east},${b.south}],[${b.east},${b.north}],[${b.west},${b.north}],[${b.west},${b.south}]]"
        """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[$ring]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}
