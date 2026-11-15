package io.github.team_oishi.oirun.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.team_oishi.oirun.OiRunApp
import io.github.team_oishi.oirun.course.CourseAnalysis
import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.CourseSort
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.Memo
import io.github.team_oishi.oirun.course.Stop
import io.github.team_oishi.oirun.course.StopDetector
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.location.GearPosition
import io.github.team_oishi.oirun.location.LocationSource
import io.github.team_oishi.oirun.record.CourseResult
import io.github.team_oishi.oirun.record.DriveService
import io.github.team_oishi.oirun.record.RecorderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { IDLE, RECORDING, REVIEW }

data class UiState(
    /** Null when the Pleos Fused Location Service is absent. */
    val source: LocationSource? = null,
    val mode: Mode = Mode.IDLE,
    val title: String = "",
    val coursePath: String? = null,
    val points: List<TrackPoint> = emptyList(),
    /** Stops read from an opened GPX; a recording finds its own from the brake. */
    val savedStops: List<Stop> = emptyList(),
    /** Memos of the course or drive shown. */
    val memos: List<Memo> = emptyList(),
    val analysis: CourseAnalysis = CourseAnalysis.EMPTY,
    val message: String? = null,
    val saved: List<SavedCourse> = emptyList(),
    val pendingDeletionCount: Int = 0,
    val courseSort: CourseSort = CourseSort.TIME,
    val sortDescending: Boolean = true,
    val loadingDistances: Boolean = false,
    /** While recording: whether a course is marked, or only the drive is being recorded. */
    val marked: Boolean = false,
    /** The whole last drive, kept until the next one; not in [saved]. */
    val recent: SavedCourse? = null,
    val gear: GearPosition = GearPosition.UNKNOWN,
) {
    val current: TrackPoint? get() = points.lastOrNull()
    /** In gear: courses are only edited while parked. */
    val driving: Boolean get() = gear == GearPosition.IN_GEAR
    /** No new course is started while parked. */
    val parked: Boolean get() = gear == GearPosition.PARK
}

class OiRunViewModel(app: Application) : AndroidViewModel(app) {

    private val analyzer = CourseAnalyzer()
    private val repository = CourseRepository(app)
    private val recorder = (app as OiRunApp).recorder
    private val stopDetector = StopDetector()
    private var analyzing: Job? = null
    private var shownWarning: String? = null
    private var loadingDistances: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val deletions = (app as OiRunApp).courseDeletions

    init {
        val pleos = recorder.source
        _state.update {
            it.copy(
                source = pleos,
                saved = visibleCourses(),
                recent = repository.recent(),
                pendingDeletionCount = deletions.pending.value.size,
                message = if (pleos == null) "Pleos 위치 서비스가 없어 기록할 수 없습니다" else null,
            )
        }
        viewModelScope.launch { recorder.state.collect(::follow) }
        viewModelScope.launch {
            (app as OiRunApp).gear.collect { gear -> _state.update { it.copy(gear = gear) } }
        }
        viewModelScope.launch {
            deletions.pending.collect { pending ->
                _state.update { it.copy(saved = visibleCourses(), pendingDeletionCount = pending.size) }
                ensureDistances()
            }
        }
        selectLatest()
        viewModelScope.launch {
            deletions.failures.collect { reportMessage("코스를 삭제하지 못했습니다. 다시 시도해 주세요") }
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.installSamples() }
                _state.update { it.copy(saved = visibleCourses()) }
                ensureDistances()
                selectLatest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not install sample courses", e)
                _state.update { it.copy(saved = visibleCourses(), message = "샘플 코스를 추가하지 못했습니다. 앱을 다시 실행해 주세요") }
            }
        }
    }

    /** Mirrors the recorder, whether the screen or the gear started it. */
    private fun follow(recording: RecorderState) {
        when (recording) {
            is RecorderState.Recording -> {
                // A warning is shown once; dismissing it shouldn't bring it back with the next fix.
                val warning = recording.message.takeIf { it != shownWarning }
                shownWarning = recording.message
                _state.update {
                    it.copy(
                        mode = Mode.RECORDING,
                        // Unmarked, the screen follows the whole drive so far.
                        title = if (recording.mark != null) "코스 기록 중" else "주행 기록 중",
                        coursePath = null,
                        marked = recording.mark != null,
                        points = if (recording.mark != null) recording.course else recording.points,
                        savedStops = emptyList(),
                        memos = if (recording.mark != null) recording.courseMemos else recording.memos.map { m -> m.memo },
                        message = warning ?: it.message.takeIf { _ -> it.mode == Mode.RECORDING },
                    )
                }
                reanalyze()
            }
            is RecorderState.Idle -> {
                // Only the end of a recording seen here changes the screen; a course saved while
                // it was closed is in the saved list.
                if (_state.value.mode != Mode.RECORDING) return
                val saved = recording.saved
                _state.update {
                    it.copy(
                        mode = if (saved != null) Mode.REVIEW else Mode.IDLE,
                        title = saved?.name ?: it.title,
                        coursePath = saved?.file?.path,
                        saved = visibleCourses(),
                        recent = repository.recent(),
                        marked = false,
                        message = recording.message,
                    )
                }
                if (saved != null) reanalyze() else selectLatest()
                ensureDistances()
            }
        }
    }

    /**
     * Call only after the source's [LocationSource.permissions] are granted. While the drive is
     * already being recorded, this marks the course from here (with the lead) instead.
     */
    fun startRecording() {
        if (_state.value.parked) return
        if (recorder.state.value is RecorderState.Recording) {
            recorder.mark()
            return
        }
        DriveService.start(getApplication())
        recorder.start(auto = false)
    }

    fun stopRecording() {
        val auto = (recorder.state.value as? RecorderState.Recording)?.auto ?: return
        viewModelScope.launch {
            val result = recorder.stop() ?: return@launch
            // A drive keeps recording after its course is saved, so the end-of-recording update never comes.
            if (auto) {
                _state.update {
                    it.copy(
                        saved = visibleCourses(),
                        message = when (result) {
                            is CourseResult.Saved -> "코스를 저장했습니다"
                            else -> result.message ?: "이동한 기록이 없어 저장하지 않았습니다"
                        },
                    )
                }
            }
        }
    }

    /** Saves the recent drive as a course and opens it. */
    fun keepRecent() {
        if (_state.value.mode == Mode.RECORDING) return
        viewModelScope.launch {
            try {
                val kept = withContext(Dispatchers.IO) { repository.keepRecent() }
                _state.update { it.copy(saved = visibleCourses(), recent = repository.recent(), message = "최근 주행을 코스로 저장했습니다") }
                open(kept)
            } catch (e: Exception) {
                Log.w(TAG, "Could not keep the recent drive", e)
                reportMessage("최근 주행을 저장하지 못했습니다")
            }
        }
    }

    /**
     * Entering the list with nothing open selects the course saved last; the
     * recent drive isn't a saved course.
     */
    fun selectLatest() {
        if (_state.value.mode != Mode.IDLE) return
        visibleCourses().maxByOrNull { it.modifiedMillis }?.let(::open)
    }

    /** Opens a course by file path, as a saved-course notification asks. */
    fun open(path: String) {
        visibleCourses().firstOrNull { it.file.path == path }?.let(::open)
    }

    fun open(course: SavedCourse) {
        if (_state.value.mode == Mode.RECORDING) return
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) { repository.load(course) }
            _state.update { it.copy(mode = Mode.REVIEW, title = track.name.ifBlank { course.name }, coursePath = course.file.path, points = track.points, savedStops = track.stops, memos = track.memos, message = null) }
            reanalyze()
        }
    }

    fun delete(course: SavedCourse) {
        if (_state.value.mode == Mode.RECORDING) return
        deletions.enqueue(course)
    }

    fun undoDelete() = deletions.undo()

    /** Sending a GPX to the phone isn't built yet; the button says so. */
    fun share() = reportMessage("이 버튼을 누르면 코스 GPX를 휴대폰으로 보냅니다 (준비 중)")

    /** Renames the open course; only while parked, like deleting. */
    fun rename(name: String) {
        val state = _state.value
        if (state.mode != Mode.REVIEW || state.driving) return
        val course = state.saved.firstOrNull { it.file.path == state.coursePath } ?: return
        if (name.trim() == state.title) return
        viewModelScope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { repository.rename(course, name) }
                _state.update {
                    it.copy(
                        saved = visibleCourses(),
                        title = if (it.coursePath == course.file.path) renamed.name else it.title,
                        coursePath = if (it.coursePath == course.file.path) renamed.file.path else it.coursePath,
                        message = "코스 이름을 바꿨습니다",
                    )
                }
            } catch (e: FileAlreadyExistsException) {
                reportMessage("같은 이름의 코스가 있습니다")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not rename course", e)
                reportMessage("코스 이름을 바꾸지 못했습니다. 다시 시도해 주세요")
            }
        }
    }

    fun setCourseSort(sort: CourseSort) {
        _state.update { it.copy(courseSort = sort) }
        ensureDistances()
    }

    fun toggleSortDirection() = _state.update { it.copy(sortDescending = !it.sortDescending) }

    private fun ensureDistances() {
        if (_state.value.courseSort != CourseSort.DISTANCE || loadingDistances?.isActive == true) return
        loadingDistances = viewModelScope.launch {
            _state.update { it.copy(loadingDistances = true) }
            try {
                while (_state.value.courseSort == CourseSort.DISTANCE) {
                    val missing = _state.value.saved.filterNot(repository::hasDistance)
                    if (missing.isEmpty()) break
                    withContext(Dispatchers.IO) { missing.forEach(repository::cacheDistance) }
                    // Re-read favorites and pending deletions after IO rather than restoring an old list.
                    _state.update { it.copy(saved = visibleCourses()) }
                }
            } finally {
                _state.update { it.copy(loadingDistances = false) }
            }
        }
    }

    fun toggleFavorite(course: SavedCourse) {
        if (_state.value.mode == Mode.RECORDING) return
        val current = _state.value.saved.firstOrNull { it.file == course.file } ?: return
        try {
            repository.setFavorite(current, !current.isFavorite)
            _state.update { it.copy(saved = visibleCourses()) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update course favorite", e)
            reportMessage("즐겨찾기를 변경하지 못했습니다. 다시 시도해 주세요")
        }
    }

    private fun visibleCourses(): List<SavedCourse> {
        val hidden = deletions.pending.value.map { it.file }.toSet()
        return repository.list().filterNot { it.file in hidden }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun refreshSavedCourses() {
        _state.update { it.copy(saved = visibleCourses(), recent = repository.recent()) }
        ensureDistances()
    }

    fun reportMessage(message: String) = _state.update { it.copy(message = message) }

    /**
     * Latest-wins: a slow analysis is dropped when a newer fix arrives. Use the recorded
     * Pleos Fused Location altitude, with the analyzer's smoothing, for both live and saved
     * courses; ground elevation must not replace the height of the driven route.
     */
    private fun reanalyze() {
        val points = _state.value.points
        val savedStops = _state.value.savedStops
        val memos = _state.value.memos
        analyzing?.cancel()
        analyzing = viewModelScope.launch {
            val analysis = withContext(Dispatchers.Default) {
                val stops = if (points.any { it.brake != null }) stopDetector.detect(points) else savedStops
                analyzer.analyze(points, stops = stops, memos = memos)
            }
            _state.update { it.copy(analysis = analysis) }
        }
    }
}

private const val TAG = "OiRun"
