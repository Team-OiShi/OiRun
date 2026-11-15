package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.Gpx
import io.github.team_oishi.oirun.course.CourseSort
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.course.sortedCourses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseSortTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun course(name: String, time: Long, distance: Double?, favorite: Boolean = false) =
        SavedCourse(File("$name.gpx"), name, time, favorite, distance)

    @Test
    fun timeDirectionKeepsFavoritesFirstAndSortsEachGroup() {
        val courses = listOf(course("old", 1, 300.0), course("new", 4, 100.0),
            course("favorite old", 2, 100.0, true), course("favorite new", 3, 300.0, true))
        assertEquals(listOf("favorite new", "favorite old", "new", "old"),
            courses.sortedCourses(CourseSort.TIME, true).map { it.name })
        assertEquals(listOf("favorite old", "favorite new", "old", "new"),
            courses.sortedCourses(CourseSort.TIME, false).map { it.name })
    }

    @Test
    fun distanceDirectionKeepsFavoritesFirstAndUnknownDistancesLast() {
        val courses = listOf(course("long", 1, 30_000.0), course("short", 2, 3_000.0),
            course("favorite short", 3, 1_000.0, true), course("favorite long", 4, 5_000.0, true),
            course("unknown", 5, null), course("favorite unknown", 6, null, true))
        assertEquals(listOf("favorite long", "favorite short", "favorite unknown", "long", "short", "unknown"),
            courses.sortedCourses(CourseSort.DISTANCE, true).map { it.name })
        assertEquals(listOf("favorite short", "favorite long", "favorite unknown", "short", "long", "unknown"),
            courses.sortedCourses(CourseSort.DISTANCE, false).map { it.name })
    }

    @Test
    fun nameDirectionKeepsFavoritesFirstAndUsesKoreanAlphabeticalOrder() {
        val courses = listOf(
            course("한강", 4, null), course("강변", 1, 300.0), course("탄천", 3, 100.0),
            course("즐겨찾기 한강", 2, null, true), course("즐겨찾기 강변", 5, 200.0, true),
        )
        assertEquals(listOf("즐겨찾기 강변", "즐겨찾기 한강", "강변", "탄천", "한강"),
            courses.sortedCourses(CourseSort.NAME, false).map { it.name })
        assertEquals(listOf("즐겨찾기 한강", "즐겨찾기 강변", "한강", "탄천", "강변"),
            courses.sortedCourses(CourseSort.NAME, true).map { it.name })
    }

    @Test
    fun namesIgnoreCaseAndUseNewestFirstForEquivalentNames() {
        val courses = listOf(course("River", 1, null), course("river", 2, null), course("Park", 3, null))
        assertEquals(listOf("Park", "river", "River"), courses.sortedCourses(CourseSort.NAME, false).map { it.name })
        assertEquals(listOf("river", "River", "Park"), courses.sortedCourses(CourseSort.NAME, true).map { it.name })
    }

    @Test
    fun equalDistancesHaveStableNewestFirstOrder() {
        val courses = listOf(course("older", 1, 100.0), course("b", 2, 100.0), course("a", 2, 100.0))
        listOf(true, false).forEach { descending ->
            assertEquals(listOf("a", "b", "older"), courses.sortedCourses(CourseSort.DISTANCE, descending).map { it.name })
        }
    }

    @Test
    fun distanceCacheMatchesCourseAnalysisAndInvalidatesChangedFiles() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val points = (0..2).map { TrackPoint(37.5 + it * 0.001, 127.0, 20.0, it * 1_000L, it * 180.0) }
        val course = repository.save(points, emptyList(), "차량 거리")
        assertNull(repository.list().single().distanceM)
        repository.cacheDistance(course)
        assertTrue(repository.hasDistance(course))
        assertEquals(CourseAnalyzer().analyze(points).totalDistanceM, repository.list().single().distanceM!!, 0.01)

        // Edited in place (saving never overwrites), so the cached distance no longer applies.
        course.file.bufferedWriter().use { Gpx.write("차량 거리", points.take(2), it) }
        val replacement = repository.list().single()
        assertFalse(repository.hasDistance(replacement))
        assertNull(repository.list().single().distanceM)
        repository.cacheDistance(replacement)
        assertEquals(CourseAnalyzer().analyze(points.take(2)).totalDistanceM, repository.list().single().distanceM!!, 0.01)
    }
}
