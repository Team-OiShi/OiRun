package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.ui.CourseDeletionQueue
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CourseDeletionQueueTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun CourseRepository.course(name: String): SavedCourse =
        save(listOf(TrackPoint(37.5, 127.0, 20.0, 0L)), emptyList(), name)

    @Test
    fun undoWithinFiveSecondsPreservesFileAndOrdering() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.course("복원할 코스")
        val bytes = course.file.readBytes()
        val modified = course.file.lastModified()
        val queue = CourseDeletionQueue(this, repository::delete) { throw it }
        queue.enqueue(course)
        queue.enqueue(course)
        assertEquals(listOf(course), queue.pending.value)
        advanceTimeBy(4_999)
        assertTrue(course.file.exists())
        queue.undo()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(queue.pending.value.isEmpty())
        assertArrayEquals(bytes, course.file.readBytes())
        assertEquals(modified, course.file.lastModified())
    }

    @Test
    fun consecutiveDeletionsHaveIndependentFiveSecondDeadlines() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val first = repository.course("첫 코스")
        val second = repository.course("다음 코스")
        val queue = CourseDeletionQueue(this, repository::delete) { throw it }
        queue.enqueue(first)
        advanceTimeBy(2_000)
        queue.enqueue(second)
        advanceTimeBy(2_999)
        assertTrue(first.file.exists())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(first.file.exists())
        assertEquals(listOf(second), queue.pending.value)
        queue.undo()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(second.file.exists())
        assertFalse(first.file.exists())
    }

    @Test
    fun closingScreenKeepsTheOriginalFiveSecondDeadline() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.course("삭제할 코스")
        var calls = 0
        val queue = CourseDeletionQueue(this, { calls++; repository.delete(it) }) { throw it }
        val screen = backgroundScope.launch { queue.pending.collect {} }
        queue.enqueue(course)
        advanceTimeBy(2_000)
        screen.cancel()
        advanceTimeBy(2_999)
        assertTrue(course.file.exists())
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
        assertFalse(course.file.exists())
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test
    fun anotherScreenCanObserveAndUndoAfterTheFirstScreenCloses() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.course("화면 전환 후 복원")
        val queue = CourseDeletionQueue(this, repository::delete) { throw it }
        val firstScreen = backgroundScope.launch { queue.pending.collect {} }
        queue.enqueue(course)
        advanceTimeBy(2_000)
        firstScreen.cancel()

        val counts = mutableListOf<Int>()
        backgroundScope.launch { queue.pending.collect { counts += it.size } }
        runCurrent()
        assertEquals(listOf(1), counts)
        queue.undo()
        runCurrent()
        assertEquals(listOf(1, 0), counts)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(course.file.exists())
    }

    @Test
    fun failedDeletionReleasesHiddenCourseAndReportsError() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.course("삭제 실패")
        var error: Exception? = null
        val queue = CourseDeletionQueue(this, { throw IOException("unavailable") }, { error = it })
        queue.enqueue(course)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(error is IOException)
        assertTrue(queue.pending.value.isEmpty())
        assertTrue(course.file.exists())
    }
}
