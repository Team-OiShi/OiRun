package io.github.team_oishi.oirun.course

/**
 * Where a course starts when the driver marks a spot mid-drive. People notice a road they like
 * after they are already on it, so the course reaches [leadM] back from the mark. The drive is
 * recorded from the start regardless, so the start can later be moved either way.
 */
data class CourseMark(val markIndex: Int, val startIndex: Int)

const val DEFAULT_LEAD_M = 500.0

/** A course this short from the mark is a mark made by mistake or a stop right after it, not a run. */
const val MIN_COURSE_M = 500.0

/**
 * The mark at the latest fix of [points], with the start [leadM] before it along the track
 * (odometer where both fixes have it, else GPS), or at the first fix if the drive is shorter.
 */
fun markLatest(points: List<TrackPoint>, leadM: Double = DEFAULT_LEAD_M): CourseMark {
    val mark = (points.size - 1).coerceAtLeast(0)
    var start = mark
    var back = 0.0
    while (start > 0 && back < leadM) {
        val a = points[start - 1]
        val b = points[start]
        val odo = a.vehicleDistanceM?.let { av -> b.vehicleDistanceM?.let { bv -> bv - av } }
        back += odo?.takeIf { it >= 0 } ?: a.distanceTo(b)
        start--
    }
    return CourseMark(mark, start)
}
