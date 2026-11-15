package io.github.team_oishi.oirun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.team_oishi.oirun.course.CourseAnalysis
import io.github.team_oishi.oirun.course.GradeBand
import io.github.team_oishi.oirun.course.Split
import kotlin.math.ceil
import kotlin.math.floor

private fun GradeBand.color() = Color(argb)

/** Altitude vs distance with a subtle gray fill that lightens toward the bottom. Drag to inspect. */
@Composable
fun ElevationProfile(analysis: CourseAnalysis, modifier: Modifier = Modifier) {
    val ink = PleosTheme.colors
    var probeM by remember(analysis.samples.size) { mutableStateOf<Double?>(null) }

    if (analysis.isEmpty) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("주행을 시작하면 고도 프로필이 그려집니다", style = MaterialTheme.typography.bodySmall, color = ink.textTertiary)
        }
        return
    }

    val samples = analysis.samples
    val total = analysis.totalDistanceM
    // Pad the y-range so a flat course doesn't read as a cliff.
    val span = (analysis.maxAltitudeM - analysis.minAltitudeM).coerceAtLeast(20.0)
    val yMin = floor((analysis.minAltitudeM - span * 0.1) / 10) * 10
    val yMax = ceil((analysis.maxAltitudeM + span * 0.1) / 10) * 10

    Column(modifier) {
        val probe = probeM?.let { d -> samples.minByOrNull { kotlin.math.abs(it.distanceM - d) } }
        // A tap within ~1 % of the course of a stop names it too.
        val probeStop = probe?.let { p -> analysis.stops.firstOrNull { kotlin.math.abs(it.distanceM - p.distanceM) <= total * 0.01 } }
        val probeMemo = probe?.let { p -> analysis.memos.firstOrNull { kotlin.math.abs(it.distanceM - p.distanceM) <= total * 0.01 } }
        Text(
            text = probe?.let {
                "%.2f km · %.0f m · 경사 %+.1f%%".format(it.distanceM / 1000, it.altitudeM, analysis.gradeAt(it.distanceM) ?: 0.0) +
                    (if (probeStop != null) " · 정지 지점" else "") +
                    (probeMemo?.let { " · 메모: ${it.memo.title}" } ?: "")
            } ?: "그래프를 눌러 지점별 고도·경사 확인",
            style = MaterialTheme.typography.labelLarge,
            color = if (probe != null) ink.textPrimary else ink.textTertiary,
            // A long memo must not squeeze the profile.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 96.dp, bottom = 8.dp),
        )
        Row(Modifier.weight(1f)) {
            Column(
                Modifier.width(96.dp).fillMaxHeight().padding(end = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text("%.0f m".format(yMax), style = MaterialTheme.typography.labelSmall, color = ink.textTertiary)
                Text("%.0f m".format(yMin), style = MaterialTheme.typography.labelSmall, color = ink.textTertiary)
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(analysis) {
                        detectTapGestures { probeM = it.x / size.width * total }
                    }
                    .pointerInput(analysis) {
                        detectDragGestures(onDragEnd = {}) { change, _ ->
                            probeM = (change.position.x / size.width * total).coerceIn(0.0, total)
                        }
                    }
            ) {
                fun x(d: Double) = (d / total * size.width).toFloat()
                fun y(alt: Double) = (size.height - (alt - yMin) / (yMax - yMin) * size.height).toFloat()

                // Recessive km gridlines.
                var km = 1000.0
                while (km < total) {
                    drawLine(ink.surfaceHigh, Offset(x(km), 0f), Offset(x(km), size.height), strokeWidth = 1f)
                    km += 1000.0
                }

                val line = Path().apply {
                    moveTo(x(samples.first().distanceM), y(samples.first().altitudeM))
                    samples.drop(1).forEach { lineTo(x(it.distanceM), y(it.altitudeM)) }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(samples.last().distanceM), size.height)
                    lineTo(x(samples.first().distanceM), size.height)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF969696), Color(0xFFD8D8D8)),
                        startY = 0f,
                        endY = size.height,
                    ),
                )

                // 2px profile line on top.
                drawPath(line, ink.textPrimary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(ink.surfaceAccent, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

                // Stops: a dot on the profile line where the car stood.
                for (s in analysis.stops) {
                    val at = samples.minBy { kotlin.math.abs(it.distanceM - s.distanceM) }
                    val c = Offset(x(s.distanceM), y(at.altitudeM))
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = c)
                    drawCircle(ink.negative, radius = 6.dp.toPx(), center = c)
                }
                // Memos: a blue dot where the driver asked for one.
                for (m in analysis.memos) {
                    val at = samples.minBy { kotlin.math.abs(it.distanceM - m.distanceM) }
                    val c = Offset(x(m.distanceM), y(at.altitudeM))
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = c)
                    drawCircle(ink.positive, radius = 6.dp.toPx(), center = c)
                }

                probe?.let {
                    val px = x(it.distanceM)
                    drawLine(
                        ink.textSecondary, Offset(px, 0f), Offset(px, size.height),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                    drawCircle(ink.surfaceBasic, radius = 7.dp.toPx(), center = Offset(px, y(it.altitudeM)))
                    drawCircle(ink.textPrimary, radius = 5.dp.toPx(), center = Offset(px, y(it.altitudeM)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 96.dp, top = 4.dp)) {
            Text("0 km", style = MaterialTheme.typography.labelSmall, color = ink.textTertiary)
            Spacer(Modifier.weight(1f))
            Text("%.1f km".format(total / 1000), style = MaterialTheme.typography.labelSmall, color = ink.textTertiary)
        }
    }
}

@Composable
fun GradeLegend(stopCount: Int, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GradeBand.entries.forEach { band ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 28.dp, height = 12.dp).background(band.color(), RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(8.dp))
                Text(band.label, style = MaterialTheme.typography.labelSmall, color = PleosTheme.colors.textSecondary)
            }
        }
        if (stopCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(PleosTheme.colors.negative, RoundedCornerShape(7.dp)))
                Spacer(Modifier.width(8.dp))
                Text("정지 $stopCount", style = MaterialTheme.typography.labelSmall, color = PleosTheme.colors.textSecondary)
            }
        }
    }
}

/** km splits; the swatch carries the band, the number carries the value. */
@Composable
fun SplitsTable(splits: List<Split>, modifier: Modifier = Modifier) {
    val ink = PleosTheme.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("구간", "거리", "평균 경사", "최대 오르막", "최대 내리막", "상승", "하강").forEachIndexed { i, h ->
                Text(
                    h, Modifier.weight(if (i == 0) 0.7f else 1f),
                    style = MaterialTheme.typography.labelLarge, color = ink.textTertiary,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                )
            }
        }
        splits.forEach { s ->
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.index} km", Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = ink.textPrimary)
                Cell("%.2f km".format(s.lengthM / 1000))
                GradeCell(s.avgGradePercent)
                GradeCell(s.maxGradePercent)
                GradeCell(s.minGradePercent)
                Cell("+%.0f m".format(s.ascentM))
                Cell("-%.0f m".format(s.descentM))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = PleosTheme.colors.textPrimary)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GradeCell(grade: Double) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(GradeBand.of(grade).color(), RoundedCornerShape(7.dp)))
        Spacer(Modifier.width(10.dp))
        Text("%+.1f%%".format(grade), style = MaterialTheme.typography.bodySmall, color = PleosTheme.colors.textPrimary)
    }
}
