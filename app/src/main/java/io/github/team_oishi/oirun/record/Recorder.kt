package io.github.team_oishi.oirun.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.SystemClock
import android.util.Log
import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.CourseMark
import io.github.team_oishi.oirun.course.DEFAULT_LEAD_M
import io.github.team_oishi.oirun.course.MIN_COURSE_M
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.ListenStart
import io.github.team_oishi.oirun.course.Memo
import io.github.team_oishi.oirun.course.shortTitle
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.StopDetector
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.course.markLatest
import io.github.team_oishi.oirun.location.LocationEvent
import io.github.team_oishi.oirun.location.PleosFusedLocationSource
import io.github.team_oishi.oirun.location.TripDistance
import io.github.team_oishi.oirun.location.VehicleBrake
import io.github.team_oishi.oirun.location.VehicleOdometer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RecorderState {
    /** [saved] is the course the last recording was saved as, if it was. */
    data class Idle(val saved: SavedCourse? = null, val message: String? = null) : RecorderState

    /**
     * [auto] when the gear started it: the whole drive is recorded but becomes a course only
     * from a [mark] on, and parking ends it. One started from the screen is marked from its start.
     */
    data class Recording(
        val points: List<TrackPoint>,
        val auto: Boolean,
        val message: String? = null,
        val mark: CourseMark? = null,
        val memos: List<DriveMemo> = emptyList(),
    ) : RecorderState {
        /** The part that becomes the course; empty until marked. */
        val course: List<TrackPoint> get() = mark?.let { points.drop(it.startIndex) }.orEmpty()

        /** Memos asked for within [course]. */
        val courseMemos: List<Memo> get() = mark?.let { m -> memos.filter { it.pointIndex >= m.startIndex }.map { it.memo } }.orEmpty()
    }
}

/** A memo during a drive, with the fix it was pinned to so a course cut from the drive keeps it. */
data class DriveMemo(val pointIndex: Int, val memo: Memo)

/** What ending a course kept. */
sealed interface CourseResult {
    data class Saved(val course: SavedCourse) : CourseResult
    /** Marked, but no more than [MIN_COURSE_M] was driven from the mark. */
    data class TooShort(val meters: Double) : CourseResult
    /** Nothing marked, or no movement. */
    data object None : CourseResult

    val saved: SavedCourse? get() = (this as? Saved)?.course
    val message: String?
        get() = when (this) {
            is Saved -> "GPX로 저장했습니다"
            is TooShort -> "표시한 지점부터 ${meters.toInt()} m라 저장하지 않았습니다 (${MIN_COURSE_M.toInt()} m 이하)"
            None -> null
        }
}

/**
 * The one recording in progress, app-wide, so it outlives the screen: [DriveService] keeps the
 * process alive while it runs, and starts and ends it with the gear.
 */
class Recorder(context: Context, private val scope: CoroutineScope) {

    val source = PleosFusedLocationSource(context).takeIf { runCatching { it.isAvailable() }.getOrDefault(false) }

    private val analyzer = CourseAnalyzer()
    private val repository = CourseRepository(context)
    private val odometer = VehicleOdometer(context)
    private val brake = VehicleBrake(context)
    private val stopDetector = StopDetector()
    private var job: Job? = null
    private var stopping = false

    private val audio = context.getSystemService(AudioManager::class.java)
    /** When each fix of this recording arrived, on [SystemClock.elapsedRealtime]. */
    private val arrivals = mutableListOf<Long>()
    private var sounds: AssistantSounds? = null

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** Call only after the source's permissions are granted. No-op while already recording. */
    fun start(auto: Boolean) {
        val source = source ?: return
        if (_state.value is RecorderState.Recording) return
        _state.value = RecorderState.Recording(emptyList(), auto, mark = if (auto) null else CourseMark(0, 0))
        arrivals.clear()
        // One per recording, so a late end of the last one can't unregister this one's.
        val playback = AssistantSounds().also { sounds = it }
        audio.registerAudioPlaybackCallback(playback, null)
        job = scope.launch {
            val trip = TripDistance()
            // Without the odometer the course is measured by GPS alone, so a failure here is not fatal.
            launch {
                try {
                    odometer.meters().collect(trip::update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No vehicle odometer; measuring distance by GPS", e)
                }
            }
            var braking: Boolean? = null
            launch {
                try {
                    brake.pressed().collect { braking = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No brake pedal status; stops are not marked", e)
                }
            }
            try {
                source.events().collect { event ->
                    when (event) {
                        is LocationEvent.Fix -> {
                            val point = event.point.copy(vehicleDistanceM = trip.meters.takeIf { trip.hasReading }, brake = braking)
                            arrivals += SystemClock.elapsedRealtime()
                            updateRecording { it.copy(points = it.points + point) }
                        }
                        is LocationEvent.Warning -> updateRecording { it.copy(message = event.message) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                _state.value = RecorderState.Idle(message = "위치 권한이 없습니다")
            } catch (e: Exception) {
                _state.value = RecorderState.Idle(message = "위치를 받을 수 없습니다")
            } finally {
                audio.unregisterAudioPlaybackCallback(playback)
            }
        }
    }

    /** Marks the course as starting [DEFAULT_LEAD_M] back from here. False if not recording or already marked. */
    fun mark(): Boolean {
        val recording = _state.value as? RecorderState.Recording ?: return false
        if (recording.mark != null) return false
        updateRecording { it.copy(mark = markLatest(it.points)) }
        return true
    }

    /** When the driver started asking Gleo for what it calls with now, if a sound shows it. */
    fun listenStart(calledAt: Long = SystemClock.elapsedRealtime()): Long? =
        sounds?.let { ListenStart.find(it.starts, calledAt) }

    /**
     * Pins [text] to where the car was at [at] (on [SystemClock.elapsedRealtime]); a blank
     * [title] is made from the text. False when there is no recording or no fix yet to pin it to.
     */
    fun addMemo(text: String, title: String, at: Long): Boolean {
        val recording = _state.value as? RecorderState.Recording ?: return false
        if (recording.points.isEmpty()) return false
        val index = arrivals.indexOfLast { it <= at }.coerceAtLeast(0).coerceAtMost(recording.points.lastIndex)
        val point = recording.points[index]
        updateRecording { it.copy(memos = it.memos + DriveMemo(index, Memo(point.latitude, point.longitude, text, title.ifBlank { shortTitle(text) }))) }
        return true
    }

    /**
     * Saves the marked course as GPX. A drive the gear started keeps recording, unmarked, for
     * the next one; one started from the screen ends. Call on the main thread; a call while
     * another is saving does nothing and returns null.
     */
    suspend fun stop(): CourseResult? = exclusive { recording ->
        val result = saveCourse(recording)
        if (recording.auto) {
            updateRecording { it.copy(mark = null) }
        } else {
            end()
            _state.value = RecorderState.Idle(result.saved, result.message ?: "이동한 기록이 없어 저장하지 않았습니다")
        }
        result
    }

    /**
     * The car parked: ends the recording, saving the marked course if there is one and keeping
     * the whole drive as the recent drive. Null if there was no recording to finish.
     */
    suspend fun finishDrive(): CourseResult? = exclusive { recording ->
        end()
        val result = saveCourse(recording)
        if (!analyzer.analyze(recording.points).isEmpty) {
            withContext(Dispatchers.IO) {
                runCatching { repository.saveRecent(recording.points, stopDetector.detect(recording.points), recording.memos.map { it.memo }) }
                    .onFailure { Log.w(TAG, "Could not keep the recent drive", it) }
            }
        }
        _state.value = RecorderState.Idle(result.saved, result.message)
        result
    }

    private suspend fun <T> exclusive(block: suspend (RecorderState.Recording) -> T): T? {
        val recording = _state.value as? RecorderState.Recording ?: return null
        if (stopping) return null
        stopping = true
        try {
            return block(recording)
        } finally {
            stopping = false
        }
    }

    private fun end() {
        job?.cancel()
        job = null
    }

    /**
     * A parked car still reports fixes; without movement there is no course to keep. The length
     * that counts is from the mark on: the lead before it is added by the app, not driven for.
     */
    private suspend fun saveCourse(recording: RecorderState.Recording): CourseResult {
        val mark = recording.mark ?: return CourseResult.None
        val points = recording.course
        if (analyzer.analyze(points).isEmpty) return CourseResult.None
        val fromMark = analyzer.analyze(recording.points.drop(mark.markIndex)).totalDistanceM
        if (fromMark <= MIN_COURSE_M) return CourseResult.TooShort(fromMark)
        return CourseResult.Saved(withContext(Dispatchers.IO) { repository.save(points, stopDetector.detect(points), memos = recording.courseMemos) })
    }

    private fun updateRecording(change: (RecorderState.Recording) -> RecorderState.Recording) =
        _state.update { if (it is RecorderState.Recording) change(it) else it }
}

/** When an assistant sound (Gleo's) started, on [SystemClock.elapsedRealtime], for the last [ListenStart.MAX_BACK_MILLIS]. */
private class AssistantSounds : AudioManager.AudioPlaybackCallback() {
    val starts = mutableListOf<Long>()
    private var playing = emptySet<AudioPlaybackConfiguration>()

    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
        // The list is what plays now; a player not in the last one has just started.
        val now = configs.filter { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }.toSet()
        if ((now - playing).isNotEmpty()) {
            val at = SystemClock.elapsedRealtime()
            starts.removeAll { at - it > ListenStart.MAX_BACK_MILLIS }
            starts += at
        }
        playing = now
    }
}

private const val TAG = "OiRun"
