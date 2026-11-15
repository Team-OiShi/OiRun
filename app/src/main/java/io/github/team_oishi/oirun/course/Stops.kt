package io.github.team_oishi.oirun.course

/**
 * Where the car stood still on the course (a light, a crossing). Only the place is kept: how long
 * the car waited depends on the light that day, which tells a runner nothing.
 */
data class Stop(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Finds stops in a recorded track: the brake held while the fixes barely move, for at least
 * [minDurationMillis], and the car moving off again afterwards. That last condition drops
 * parking at the end of a drive without needing the gear.
 */
class StopDetector(
    private val minDurationMillis: Long = 3_000,
    private val maxSpeedMps: Double = 1.5,
    private val moveOffM: Double = 15.0,
) {

    fun detect(points: List<TrackPoint>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var i = 0
        while (i < points.size) {
            if (!still(points, i)) {
                i++
                continue
            }
            var end = i
            while (end + 1 < points.size && still(points, end + 1)) end++
            val run = points.subList(i, end + 1)
            val duration = run.last().timeMillis - run.first().timeMillis
            if (duration >= minDurationMillis) {
                val lat = run.sumOf { it.latitude } / run.size
                val lon = run.sumOf { it.longitude } / run.size
                val movedOff = points.subList(end + 1, points.size)
                    .any { distanceMeters(lat, lon, it.latitude, it.longitude) > moveOffM }
                if (movedOff) stops += Stop(lat, lon)
            }
            i = end + 1
        }
        return stops
    }

    /** Brake on and, against the previous fix, slower than walking. */
    private fun still(points: List<TrackPoint>, i: Int): Boolean {
        val p = points[i]
        if (p.brake != true) return false
        if (i == 0) return true
        val prev = points[i - 1]
        val dt = (p.timeMillis - prev.timeMillis) / 1000.0
        return dt <= 0 || p.distanceTo(prev) / dt <= maxSpeedMps
    }
}
