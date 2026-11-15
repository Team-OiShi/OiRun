package io.github.team_oishi.oirun.location

import android.annotation.SuppressLint
import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The car's own distance count in meters, which comes from wheel travel, so it holds through
 * tunnels and GPS jumps where fix-to-fix distance does not. It reads the trip distance (m)
 * and, where the vehicle lacks that, the odometer (km). The flow ends with an error when
 * the vehicle service has neither.
 */
class VehicleOdometer(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun meters(): Flow<Double> = callbackFlow {
        val vehicle = Vehicle(context.applicationContext)
        vehicle.initialize()
        val odometer = vehicle.getOdometer()
        val total = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                // Widened first: a Float in meters is only good to ~8 m at 100,000 km.
                value?.let { trySend(it.toDouble() * 1000) }
            }

            override fun onFailed(e: Exception) {
                close(e)
            }
        }
        val trip = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                value?.let { trySend(it.toDouble()) }
            }

            override fun onFailed(e: Exception) {
                runCatching { odometer.unregisterTripCurrentDrivingDistance(this) }
                try {
                    odometer.registerDistanceDriven(total)
                } catch (e: Exception) {
                    close(e)
                }
            }
        }
        odometer.registerTripCurrentDrivingDistance(trip)
        awaitClose {
            runCatching { odometer.unregisterTripCurrentDrivingDistance(trip) }
            runCatching { odometer.unregisterDistanceDriven(total) }
            vehicle.release()
        }
    }
}

/**
 * Distance since recording started, from raw trip readings. The trip counter restarts with
 * the driving session, so a reading below the last one is taken as a fresh count from zero.
 */
class TripDistance {
    private var last: Double? = null

    var meters: Double = 0.0
        private set

    /** False until the first reading, which only sets the baseline. */
    val hasReading: Boolean get() = last != null

    fun update(raw: Double) {
        val prev = last
        last = raw
        if (prev == null) return
        meters += if (raw >= prev) raw - prev else raw
    }
}
