package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.ui.CourseDeletionQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class CourseFavoritesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun CourseRepository.course(name: String, modified: Long): SavedCourse =
        save(listOf(TrackPoint(37.5, 127.0, 20.0, 0L)), emptyList(), name).also {
            assertTrue(it.file.setLastModified(modified))
        }

    @Test
    fun favoritesStayAboveNewerCoursesAndKeepDateOrderWithinEachGroup() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val oldFavorite = repository.course("오래된 즐겨찾기", 1_000)
        val newFavorite = repository.course("새 즐겨찾기", 2_000)
        repository.course("일반 코스", 3_000)
        repository.setFavorite(oldFavorite, true)
        repository.setFavorite(newFavorite, true)
        repository.course("방금 저장한 코스", 4_000)

        assertEquals(
            listOf("새 즐겨찾기", "오래된 즐겨찾기", "방금 저장한 코스", "일반 코스"),
            repository.list().map { it.name },
        )
        repository.setFavorite(newFavorite, false)
        assertEquals(
            listOf("오래된 즐겨찾기", "방금 저장한 코스", "일반 코스", "새 즐겨찾기"),
            repository.list().map { it.name },
        )
    }

    @Test
    fun favoritesPersistWithoutModifyingGpxOrRecordedDate() {
        val dir = temporaryFolder.newFolder()
        val repository = CourseRepository(dir)
        val course = repository.course("즐겨찾기 코스", 1_000)
        val bytes = course.file.readBytes()
        repository.setFavorite(course, true)

        val reopened = CourseRepository(dir)
        assertTrue(reopened.list().single().isFavorite)
        assertEquals(1_000L, reopened.list().single().modifiedMillis)
        assertArrayEquals(bytes, course.file.readBytes())
        reopened.setFavorite(course, false)
        assertFalse(CourseRepository(dir).list().single().isFavorite)
        assertArrayEquals(bytes, course.file.readBytes())
    }

    @Test
    fun undoPreservesFavoriteButConfirmedDeletionClearsIt() = runTest {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.course("삭제할 즐겨찾기", 1_000)
        repository.setFavorite(course, true)
        val queue = CourseDeletionQueue(this, repository::delete) { throw it }
        queue.enqueue(repository.list().single())
        advanceTimeBy(4_000)
        queue.undo()
        assertTrue(repository.list().single().isFavorite)

        queue.enqueue(repository.list().single())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(repository.list().isEmpty())
        repository.course(course.name, 2_000)
        assertFalse(repository.list().single().isFavorite)
    }
}
