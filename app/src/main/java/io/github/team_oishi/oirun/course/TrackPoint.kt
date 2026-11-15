package io.github.team_oishi.oirun.course

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One raw location fix recorded while driving the course. [vehicleDistanceM] is the car's
 * odometer distance since recording started and [brake] the brake pedal, both as of this fix;
 * null without a vehicle reading.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timeMillis: Long,
    val vehicleDistanceM: Double? = null,
    val brake: Boolean? = null,
)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in meters. */
fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun TrackPoint.distanceTo(other: TrackPoint): Double =
    distanceMeters(latitude, longitude, other.latitude, other.longitude)
