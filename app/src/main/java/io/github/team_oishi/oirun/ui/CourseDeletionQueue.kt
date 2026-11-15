package io.github.team_oishi.oirun.ui

import io.github.team_oishi.oirun.course.SavedCourse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Main-thread queue; files remain intact until each course's undo window expires. */
internal class CourseDeletionQueue(
    private val scope: CoroutineScope,
    private val delete: (SavedCourse) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val _pending = MutableStateFlow<List<SavedCourse>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _failures = MutableSharedFlow<Exception>(extraBufferCapacity = 1)
    val failures = _failures.asSharedFlow()

    fun enqueue(course: SavedCourse) {
        if (_pending.value.any { it.file == course.file }) return
        _pending.value += course
        jobs[course.file.path] = scope.launch {
            delay(5_000)
            commit(course)
        }
    }

    fun undo() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _pending.value = emptyList()
    }

    private fun commit(course: SavedCourse) {
        try {
            delete(course)
        } catch (e: Exception) {
            onFailure(e)
            _failures.tryEmit(e)
        } finally {
            jobs.remove(course.file.path)
            _pending.value = _pending.value.filterNot { it.file == course.file }
        }
    }
}
