package io.github.team_oishi.oirun.location

import android.annotation.SuppressLint
import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Brake pedal pressed or not (Vehicle SDK, Brake). Ends with an error when the car lacks it. */
class VehicleBrake(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun pressed(): Flow<Boolean> = callbackFlow {
        val vehicle = Vehicle(context.applicationContext)
        vehicle.initialize()
        val brake = vehicle.getBrake()
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                status?.let { trySend(it) }
            }

            override fun onFailed(e: Exception) {
                close(e)
            }
        }
        brake.registerBrakePedalStatus(listener)
        awaitClose {
            runCatching { brake.unregisterBrakePedalStatus(listener) }
            vehicle.release()
        }
    }
}
