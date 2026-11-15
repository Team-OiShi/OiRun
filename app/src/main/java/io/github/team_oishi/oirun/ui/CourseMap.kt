package io.github.team_oishi.oirun.ui

import android.graphics.PointF
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.team_oishi.oirun.course.CourseAnalysis
import io.github.team_oishi.oirun.course.CourseMemo
import io.github.team_oishi.oirun.course.GradeBand
import io.github.team_oishi.oirun.course.TrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Free, key-less vector tiles (OpenStreetMap data); the dark style follows night mode. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

private const val COURSE_SOURCE = "course"
private const val POSITION_SOURCE = "position"
private const val STOPS_SOURCE = "stops"
private const val MEMOS_SOURCE = "memos"

/** Stops wear the guide's negative color, the same red as the stop button. */
private const val STOP_COLOR = "#FE3D16"

/** Memos wear the guide's positive blue, apart from the stops and the grade colors. */
private const val MEMO_COLOR = "#0064FF"

/**
 * Course drawn as one line per [io.github.team_oishi.oirun.course.GradeSegment], colored
 * by [GradeBand], with a red dot where the car stopped and a blue one at each memo. While recording the camera follows the
 * car; in review it frames the course and each memo's title floats over its dot (see
 * [MemoCallouts]). Callouts stay off the recording screen, which is read while driving.
 */
@Composable
fun CourseMap(
    analysis: CourseAnalysis,
    current: TrackPoint?,
    follow: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(dark) { MapController(if (dark) STYLE_URL_DARK else STYLE_URL, dark) }
    val mapView = remember(dark) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync(controller::attach)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
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
            mapView.onDestroy()
        }
    }

    // The callouts follow the camera, so each move places them again.
    var camera by remember(controller) { mutableIntStateOf(0) }
    var selected by remember(analysis.memos) { mutableStateOf<CourseMemo?>(null) }
    val showMemos = !follow && analysis.memos.isNotEmpty()
    val tapSlop = with(LocalDensity.current) { 32.dp.toPx() }
    SideEffect {
        controller.onCameraMove = { camera++ }
        // A tap on a dot opens its memo; anywhere else closes it.
        controller.onTap = { tap ->
            selected = if (!showMemos) null else analysis.memos
                .mapNotNull { m -> controller.toScreen(m)?.let { m to kotlin.math.hypot(it.x - tap.x, it.y - tap.y) } }
                .filter { it.second <= tapSlop }
                .minByOrNull { it.second }?.first
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { controller.render(analysis, current, follow) },
        )
        if (showMemos) {
            MemoCallouts(
                memos = analysis.memos,
                anchor = controller::toScreen,
                camera = { camera },
                selected = selected,
                onSelect = { selected = if (selected == it) null else it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A small card above each memo dot with the memo's title; the selected one opens to the whole
 * note and where it is. A card goes above its dot, or below, right or left of it where that
 * would overlap another; where all would, the earlier one along the course stays and the rest keep just their dot, so a cluster of memos doesn't bury the course. The selected card
 * is placed first.
 */
@Composable
private fun MemoCallouts(
    memos: List<CourseMemo>,
    anchor: (CourseMemo) -> PointF?,
    camera: () -> Int,
    selected: CourseMemo?,
    onSelect: (CourseMemo) -> Unit,
    modifier: Modifier,
) {
    val c = PleosTheme.colors
    val order = memos.sortedBy { it.distanceM }.sortedByDescending { it == selected }
    val gap = with(LocalDensity.current) { 14.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            order.forEach { memo ->
                val open = memo == selected
                Column(
                    Modifier
                        .shadow(6.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surfaceBasic)
                        .clickable { onSelect(memo) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .widthIn(max = if (open) 420.dp else 240.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(c.positive, RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            memo.memo.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (open) {
                        Text(memo.memo.text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                        Text("%.2f km 지점".format(memo.distanceM / 1000), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            camera() // read here so a camera move only re-places the cards
            val taken = mutableListOf<Rect>()
            placeables.forEachIndexed { i, card ->
                val at = anchor(order[i]) ?: return@forEachIndexed
                if (at.x < 0 || at.y < 0 || at.x > constraints.maxWidth || at.y > constraints.maxHeight) return@forEachIndexed
                // Above the dot, else below, right or left of it, pushed inside the map at its edges.
                val w = card.width.toFloat()
                val h = card.height.toFloat()
                val rect = listOf(
                    at.x - w / 2 to at.y - h - gap,
                    at.x - w / 2 to at.y + gap,
                    at.x + gap to at.y - h / 2,
                    at.x - w - gap to at.y - h / 2,
                ).map { (left, top) ->
                    val l = left.coerceIn(0f, (constraints.maxWidth - w).coerceAtLeast(0f))
                    val t = top.coerceIn(0f, (constraints.maxHeight - h).coerceAtLeast(0f))
                    Rect(l, t, l + w, t + h)
                }.firstOrNull { r -> taken.none { it.overlaps(r) } } ?: return@forEachIndexed
                taken += rect
                card.place(rect.left.toInt(), rect.top.toInt())
            }
        }
    }
}

private class MapController(private val styleUrl: String, private val dark: Boolean) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pending: Triple<CourseAnalysis, TrackPoint?, Boolean>? = null
    private var framedCourse: CourseAnalysis? = null
    var onCameraMove: () -> Unit = {}
    var onTap: (PointF) -> Unit = {}

    fun toScreen(memo: CourseMemo): PointF? =
        map?.projection?.toScreenLocation(LatLng(memo.memo.latitude, memo.memo.longitude))

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.addOnCameraMoveListener { onCameraMove() }
        map.addOnCameraIdleListener { onCameraMove() }
        map.addOnMapClickListener { onTap(map.projection.toScreenLocation(it)); false }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.5665, 126.9780), 12.0))
        map.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            loaded.addSource(GeoJsonSource(COURSE_SOURCE))
            loaded.addSource(GeoJsonSource(POSITION_SOURCE))
            loaded.addSource(GeoJsonSource(STOPS_SOURCE))
            loaded.addSource(GeoJsonSource(MEMOS_SOURCE))
            loaded.addLayer(
                LineLayer("course-casing", COURSE_SOURCE).withProperties(
                    PropertyFactory.lineColor(if (dark) "#F7F8FA" else "#131417"),
                    PropertyFactory.lineWidth(11f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            loaded.addLayer(
                LineLayer("course-grade", COURSE_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            loaded.addLayer(
                CircleLayer("stops", STOPS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(STOP_COLOR),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                )
            )
            loaded.addLayer(
                CircleLayer("memos", MEMOS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(MEMO_COLOR),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                )
            )
            loaded.addLayer(
                CircleLayer("position", POSITION_SOURCE).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleStrokeColor("#131417"),
                    PropertyFactory.circleStrokeWidth(4f),
                )
            )
            style = loaded
            pending?.let { (a, c, f) -> render(a, c, f) }
        }
    }

    fun render(analysis: CourseAnalysis, current: TrackPoint?, follow: Boolean) {
        val map = map
        val style = style
        if (map == null || style == null) {
            pending = Triple(analysis, current, follow)
            return
        }
        pending = null

        val features = analysis.segments.map { seg ->
            Feature.fromGeometry(
                LineString.fromLngLats(seg.path.map { Point.fromLngLat(it.longitude, it.latitude) })
            ).apply { addStringProperty("color", GradeBand.of(seg.gradePercent).hex) }
        }
        style.getSourceAs<GeoJsonSource>(COURSE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))

        style.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                analysis.stops.map { Feature.fromGeometry(Point.fromLngLat(it.stop.longitude, it.stop.latitude)) }
            )
        )
        style.getSourceAs<GeoJsonSource>(MEMOS_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                analysis.memos.map { Feature.fromGeometry(Point.fromLngLat(it.memo.longitude, it.memo.latitude)) }
            )
        )

        val position = if (follow) current else null
        style.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(position?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })
            )
        )

        if (follow && current != null) {
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(current.latitude, current.longitude), 15.5), 300)
        } else if (!follow && analysis.samples.size >= 2 && framedCourse !== analysis) {
            framedCourse = analysis
            val bounds = LatLngBounds.Builder().apply {
                analysis.samples.forEach { include(LatLng(it.latitude, it.longitude)) }
            }.build()
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 600)
        }
    }
}
