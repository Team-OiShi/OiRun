package io.github.team_oishi.oirun.course

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A resampled, altitude-smoothed point at a fixed distance along the course. */
data class CourseSample(
    val distanceM: Double,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
)

/** A short stretch of the course drawn in a single grade color. */
data class GradeSegment(
    val startM: Double,
    val endM: Double,
    val gradePercent: Double,
    val path: List<CourseSample>,
)

data class Split(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val ascentM: Double,
    val descentM: Double,
    val avgGradePercent: Double,
    val maxGradePercent: Double,
    val minGradePercent: Double,
) {
    val lengthM: Double get() = endM - startM
}

data class CourseAnalysis(
    val samples: List<CourseSample>,
    val segments: List<GradeSegment>,
    val splits: List<Split>,
    val totalDistanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val minAltitudeM: Double,
    val maxAltitudeM: Double,
    /** What [totalDistanceM] and every distanceM along the course were measured with. */
    val distanceSource: DistanceSource = DistanceSource.GPS,
    /** What every altitude along the course came from. */
    val altitudeSource: AltitudeSource = AltitudeSource.GPS,
    val stops: List<CourseStop> = emptyList(),
    val memos: List<CourseMemo> = emptyList(),
    /** Fix-to-fix GPS distance, kept for comparison when the odometer measured the course. */
    val gpsDistanceM: Double = totalDistanceM,
) {
    val isEmpty: Boolean get() = samples.size < 2

    /** Grade of the segment that contains [distanceM], or null before any segment exists. */
    fun gradeAt(distanceM: Double): Double? =
        segments.lastOrNull { it.startM <= distanceM }?.gradePercent

    companion object {
        val EMPTY = CourseAnalysis(emptyList(), emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

enum class DistanceSource { GPS, VEHICLE }

enum class AltitudeSource { GPS, TERRAIN }

/** A [Stop] placed on the course: [distanceM] is where along it the car stood. */
data class CourseStop(val stop: Stop, val distanceM: Double)

/** A [Memo] placed on the course: [distanceM] is where along it the driver asked for it. */
data class CourseMemo(val memo: Memo, val distanceM: Double)

/**
 * Turns a raw drive track into running metrics.
 *
 * Fused-location altitude jitters by a few meters, which over a 10 m step reads as a
 * double-digit grade. So the track is resampled at a fixed spacing, altitude is smoothed
 * over [smoothingWindowM], grade is measured over [segmentLengthM], and ascent only
 * counts once a climb exceeds [elevationThresholdM].
 *
 * Distance along the course comes from the car's odometer when the track carries it
 * (see [vehicleDistances]); GPS positions then only say where on the map each meter lies.
 *
 * Altitude comes from terrain data when a lookup is given (see [terrainAltitudes]). The ~30 m
 * terrain grid blends a mountain road with the slope beside it, so it is smoothed over the
 * wider [terrainSmoothingWindowM].
 */
class CourseAnalyzer(
    private val sampleSpacingM: Double = 10.0,
    private val smoothingWindowM: Double = 60.0,
    private val segmentLengthM: Double = 50.0,
    private val splitLengthM: Double = 1000.0,
    private val elevationThresholdM: Double = 2.0,
    private val minMoveM: Double = 1.0,
    private val terrainSmoothingWindowM: Double = 100.0,
    private val structureToleranceM: Double = 25.0,
    private val minTerrainCoverage: Double = 0.8,
    private val odometerSlackM: Double = 100.0,
) {

    fun analyze(points: List<TrackPoint>, terrain: ElevationLookup? = null, stops: List<Stop> = emptyList(), memos: List<Memo> = emptyList()): CourseAnalysis {
        val moving = dropStationary(points)
        if (moving.size < 2) return CourseAnalysis.EMPTY

        val gps = gpsDistances(moving)
        val vehicle = vehicleDistances(moving, gps)
        val raw = resample(moving, vehicle ?: gps)
        val ground = terrain?.let { terrainAltitudes(raw, it) }
        val samples = if (ground != null) {
            smooth(raw.mapIndexed { i, s -> s.copy(altitudeM = ground[i]) }, terrainSmoothingWindowM)
        } else {
            smooth(raw, smoothingWindowM)
        }
        if (samples.size < 2) return CourseAnalysis.EMPTY

        val segments = segments(samples)
        val altitudes = samples.map { it.altitudeM }
        val (ascent, descent) = climb(altitudes)
        return CourseAnalysis(
            samples = samples,
            segments = segments,
            splits = splits(samples, segments),
            totalDistanceM = samples.last().distanceM,
            ascentM = ascent,
            descentM = descent,
            minAltitudeM = altitudes.min(),
            maxAltitudeM = altitudes.max(),
            distanceSource = if (vehicle != null) DistanceSource.VEHICLE else DistanceSource.GPS,
            altitudeSource = if (ground != null) AltitudeSource.TERRAIN else AltitudeSource.GPS,
            stops = stops.map { stop -> CourseStop(stop, alongM(samples, stop.latitude, stop.longitude)) },
            memos = memos.map { memo -> CourseMemo(memo, alongM(samples, memo.latitude, memo.longitude)) },
            gpsDistanceM = gps.last(),
        )
    }

    /** How far along the course the sample nearest to a place lies. */
    private fun alongM(samples: List<CourseSample>, latitude: Double, longitude: Double): Double =
        samples.minBy { distanceMeters(it.latitude, it.longitude, latitude, longitude) }.distanceM

    /** Stops at lights would otherwise pile up fixes at one spot. */
    private fun dropStationary(points: List<TrackPoint>): List<TrackPoint> {
        if (points.isEmpty()) return points
        val kept = mutableListOf(points.first())
        for (p in points.drop(1)) {
            if (p.distanceTo(kept.last()) >= minMoveM) kept += p
        }
        return kept
    }

    private fun gpsDistances(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + points[i - 1].distanceTo(points[i])
        return out
    }

    /**
     * Distance along the course from the odometer, or null when the track has under two
     * distinct readings. The odometer ticks coarser than fixes arrive, so only the fixes
     * where its reading changed are pinned to it; the fixes between are spread by their
     * share of the GPS distance. Before the first reading and after the last, the GPS
     * distance is scaled by how the two compared over the pinned stretch.
     *
     * A reading can be stale: the first one after subscribing may be the vehicle service's
     * last cached value, and the next one then leaps ahead of the car. A step the GPS could
     * not have covered (over twice its distance plus [odometerSlackM]) is taken as such a
     * break and replaced by the GPS distance; later steps count on from there.
     */
    private fun vehicleDistances(points: List<TrackPoint>, gps: DoubleArray): DoubleArray? {
        val anchors = mutableListOf<Int>()
        points.forEachIndexed { i, p ->
            val v = p.vehicleDistanceM ?: return@forEachIndexed
            if (anchors.isEmpty() || v > points[anchors.last()].vehicleDistanceM!!) anchors += i
        }
        if (anchors.size < 2) return null

        val pinned = HashMap<Int, Double>()
        pinned[anchors.first()] = points[anchors.first()].vehicleDistanceM!!
        anchors.zipWithNext { a, b ->
            val step = points[b].vehicleDistanceM!! - points[a].vehicleDistanceM!!
            val span = gps[b] - gps[a]
            pinned[b] = pinned.getValue(a) + if (step > span * 2 + odometerSlackM) span else step
        }
        fun odo(i: Int) = pinned.getValue(i)
        val out = DoubleArray(points.size)
        val first = anchors.first()
        val last = anchors.last()
        val pinnedGps = gps[last] - gps[first]
        val scale = if (pinnedGps > 0) (odo(last) - odo(first)) / pinnedGps else 1.0
        for (i in 0 until first) out[i] = odo(first) - (gps[first] - gps[i]) * scale
        anchors.zipWithNext { a, b ->
            val span = gps[b] - gps[a]
            for (i in a until b) {
                val share = if (span > 0) (gps[i] - gps[a]) / span else (i - a).toDouble() / (b - a)
                out[i] = odo(a) + (odo(b) - odo(a)) * share
            }
        }
        for (i in last until points.size) out[i] = odo(last) + (gps[i] - gps[last]) * scale
        val origin = out[0]
        return DoubleArray(out.size) { out[it] - origin }
    }

    private fun resample(points: List<TrackPoint>, along: DoubleArray): List<CourseSample> {
        val out = mutableListOf(CourseSample(0.0, points[0].latitude, points[0].longitude, points[0].altitude))
        var travelled = 0.0
        var next = sampleSpacingM
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val step = along[i] - along[i - 1]
            if (step <= 0) continue
            while (next <= travelled + step) {
                val t = (next - travelled) / step
                out += CourseSample(
                    distanceM = next,
                    latitude = a.latitude + (b.latitude - a.latitude) * t,
                    longitude = a.longitude + (b.longitude - a.longitude) * t,
                    altitudeM = a.altitude + (b.altitude - a.altitude) * t,
                )
                next += sampleSpacingM
            }
            travelled += step
        }
        // Keep the tail so the course ends where the drive did.
        if (travelled - out.last().distanceM > sampleSpacingM / 2) {
            val last = points.last()
            out += CourseSample(travelled, last.latitude, last.longitude, last.altitude)
        }
        return out
    }

    /**
     * Ground elevation under each sample, or null when terrain covers too little of the course.
     * On a bridge or in a tunnel the ground is far from the road; those samples stand out
     * against the GPS altitude (after taking out the steady gap between the two, as GPS heights
     * can sit tens of meters off sea level) and are bridged by a straight line between the
     * ground at either end, as is a sample with no terrain under it.
     */
    private fun terrainAltitudes(samples: List<CourseSample>, terrain: ElevationLookup): DoubleArray? {
        val ground = samples.map { terrain.elevationAt(it.latitude, it.longitude) }
        val gps = smooth(samples, smoothingWindowM).map { it.altitudeM }
        val gaps = ground.indices.mapNotNull { i -> ground[i]?.let { it - gps[i] } }.sorted()
        if (gaps.isEmpty()) return null
        val offset = gaps[gaps.size / 2]
        val valid = BooleanArray(samples.size) { i ->
            ground[i]?.let { abs(it - gps[i] - offset) <= structureToleranceM } == true
        }
        if (valid.count { it } < samples.size * minTerrainCoverage) return null

        val out = DoubleArray(samples.size)
        var lastValid = -1
        for (i in samples.indices) {
            if (!valid[i]) continue
            out[i] = ground[i]!!
            if (i - lastValid > 1) {
                // Fill the run (lastValid, i): a straight line, or flat at the course's start.
                for (j in lastValid + 1 until i) {
                    out[j] = if (lastValid < 0) {
                        out[i]
                    } else {
                        val t = (samples[j].distanceM - samples[lastValid].distanceM) /
                            (samples[i].distanceM - samples[lastValid].distanceM)
                        out[lastValid] + (out[i] - out[lastValid]) * t
                    }
                }
            }
            lastValid = i
        }
        for (j in lastValid + 1 until samples.size) out[j] = out[lastValid]
        return out
    }

    private fun smooth(samples: List<CourseSample>, windowM: Double): List<CourseSample> {
        val half = max(0, (windowM / sampleSpacingM / 2).toInt())
        if (half == 0) return samples
        return samples.mapIndexed { i, s ->
            val from = max(0, i - half)
            val to = min(samples.lastIndex, i + half)
            val avg = (from..to).sumOf { samples[it].altitudeM } / (to - from + 1)
            s.copy(altitudeM = avg)
        }
    }

    private fun segments(samples: List<CourseSample>): List<GradeSegment> {
        val out = mutableListOf<GradeSegment>()
        var start = 0
        while (start < samples.lastIndex) {
            var end = start + 1
            while (end < samples.lastIndex && samples[end].distanceM - samples[start].distanceM < segmentLengthM) end++
            val a = samples[start]
            val b = samples[end]
            val run = b.distanceM - a.distanceM
            out += GradeSegment(
                startM = a.distanceM,
                endM = b.distanceM,
                gradePercent = if (run > 0) (b.altitudeM - a.altitudeM) / run * 100 else 0.0,
                path = samples.subList(start, end + 1).toList(),
            )
            start = end
        }
        return out
    }

    /** Hysteresis: the reference only moves once the climb or drop clears the threshold. */
    private fun climb(altitudes: List<Double>): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        var ref = altitudes.first()
        for (alt in altitudes) {
            val delta = alt - ref
            if (abs(delta) >= elevationThresholdM) {
                if (delta > 0) ascent += delta else descent -= delta
                ref = alt
            }
        }
        return ascent to descent
    }

    private fun splits(samples: List<CourseSample>, segments: List<GradeSegment>): List<Split> {
        val total = samples.last().distanceM
        val out = mutableListOf<Split>()
        var startM = 0.0
        var index = 1
        while (startM < total - 1e-6) {
            val endM = min(startM + splitLengthM, total)
            val inSplit = samples.filter { it.distanceM in startM..endM }
            val grades = segments
                .filter { it.endM > startM && it.startM < endM }
                .map { it.gradePercent }
            if (inSplit.size >= 2) {
                val (ascent, descent) = climb(inSplit.map { it.altitudeM })
                val length = inSplit.last().distanceM - inSplit.first().distanceM
                out += Split(
                    index = index,
                    startM = startM,
                    endM = endM,
                    ascentM = ascent,
                    descentM = descent,
                    avgGradePercent = if (length > 0) (inSplit.last().altitudeM - inSplit.first().altitudeM) / length * 100 else 0.0,
                    maxGradePercent = grades.maxOrNull() ?: 0.0,
                    minGradePercent = grades.minOrNull() ?: 0.0,
                )
            }
            startM = endM
            index++
        }
        return out
    }
}
