package io.github.team_oishi.oirun.location

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class GearPosition { UNKNOWN, PARK, IN_GEAR }

private fun gearPosition(status: Int, value: Any?): GearPosition {
    if (status != CarPropertyValue.STATUS_AVAILABLE) return GearPosition.UNKNOWN
    return when (value) {
        VehicleGear.GEAR_PARK -> GearPosition.PARK
        VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL,
        VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
        VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH,
        VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> GearPosition.IN_GEAR
        else -> GearPosition.UNKNOWN
    }
}

private class GearPropertyCallback(private val send: (GearPosition) -> Unit) :
    CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<*>) {
        if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
        // An unavailable reading must invalidate the last known park state.
        send(gearPosition(value.status, value.value))
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        if (propertyId == VehiclePropertyIds.GEAR_SELECTION) send(GearPosition.UNKNOWN)
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int, errorCode: Int) {
        onErrorEvent(propertyId, areaId)
    }
}

/**
 * The selected gear, from the platform's GEAR_SELECTION property (CAR_POWERTRAIN, a normal
 * permission); the Vehicle SDK's own gear listener is deprecated in its favor. Only the
 * park/not-park distinction matters here: D, R, N and manual gears all count as driving.
 * Invalid readings emit UNKNOWN so consumers cannot retain a stale park state. The flow
 * stays subscribed for recovery, but ends with an error if setup fails.
 */
class VehicleGear(private val context: Context) {

    fun positions(): Flow<GearPosition> = callbackFlow {
        val car = Car.createCar(context.applicationContext) ?: throw IllegalStateException("No car service")
        val properties = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val callback = GearPropertyCallback { trySend(it) }
        @Suppress("DEPRECATION") // subscribePropertyEvents needs API 35; the car may be older.
        val registered = properties.registerCallback(
            callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )
        if (!registered) {
            car.disconnect()
            throw IllegalStateException("GEAR_SELECTION is not available")
        }
        awaitClose {
            @Suppress("DEPRECATION")
            properties.unregisterCallback(callback)
            car.disconnect()
        }
    }.distinctUntilChanged()
}
