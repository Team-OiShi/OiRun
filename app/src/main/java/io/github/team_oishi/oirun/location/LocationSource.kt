package io.github.team_oishi.oirun.location

import android.annotation.SuppressLint
import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.constant.ErrorCode
import ai.pleos.playground.location.fused.constant.PleosPermission
import ai.pleos.playground.location.fused.exception.LocationException
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import io.github.team_oishi.oirun.course.TrackPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface LocationEvent {
    data class Fix(val point: TrackPoint) : LocationEvent

    /** Non-fatal: the stream keeps running (e.g. the car started moving). */
    data class Warning(val message: String) : LocationEvent
}

interface LocationSource {
    val title: String
    val description: String

    /** Runtime permissions that must be granted before [events] is collected. */
    val permissions: List<String>

    fun isAvailable(): Boolean

    fun events(): Flow<LocationEvent>
}

/**
 * Vehicle-fused position (GNSS + vehicle sensors) from the Pleos Fused Location Service,
 * the app's only source so the emulator exercises the same path as the car. On the
 * emulator the service follows the emulated GPS.
 */
class PleosFusedLocationSource(private val context: Context) : LocationSource {
    override val title = "Pleos Fused Location"
    override val description = "차량 센서 융합 위치·고도"
    override val permissions = listOf(PleosPermission.FUSED_LOCATION)

    override fun isAvailable(): Boolean = FusedLocation(context).isSupported()

    @SuppressLint("MissingPermission")
    override fun events(): Flow<LocationEvent> = callbackFlow {
        val fused = FusedLocation(context)
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                trySend(
                    LocationEvent.Fix(
                        TrackPoint(location.latitude, location.longitude, location.altitude, location.timestampMillis)
                    )
                )
            }

            override fun onError(exception: Exception) {
                val code = (exception as? LocationException)?.errorCode
                when (code) {
                    ErrorCode.ERROR_DRIVING_RESTRICTED ->
                        trySend(LocationEvent.Warning("주행 중 위치 접근이 제한되었습니다"))
                    ErrorCode.ERROR_LOCATION_DISABLED -> {
                        fused.showLocationAccessBlockedPopup()
                        trySend(LocationEvent.Warning("시스템 설정에서 위치 공유가 꺼져 있습니다"))
                    }
                    else -> trySend(LocationEvent.Warning("위치 오류: ${code ?: exception.message}"))
                }
            }
        }
        fused.initialize()
        fused.registerFusedLocationCallback(listener)
        awaitClose { fused.release() }
    }
}
