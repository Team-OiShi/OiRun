package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.CourseMark
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.RECENT_NAME
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.course.distanceTo
import io.github.team_oishi.oirun.course.markLatest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseMarkTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** Fixes every [stepM] meters due north, with the odometer if [odometer]. */
    private fun drive(count: Int, stepM: Double, odometer: Boolean = false) = List(count) { i ->
        TrackPoint(37.5 + i * stepM / 111_195.0, 127.0, 20.0, i * 1000L, vehicleDistanceM = if (odometer) i * stepM else null)
    }

    @Test
    fun markReachesTheLeadBackAlongTheDrive() {
        // 100 fixes 21 m apart: the mark is the last one, the start the first fix at least
        // 500 m before it, 24 fixes (504 m) back.
        assertEquals(CourseMark(99, 75), markLatest(drive(100, 21.0)))
    }

    @Test
    fun markEarlyInTheDriveStartsAtTheFirstFix() {
        assertEquals(CourseMark(9, 0), markLatest(drive(10, 20.0)))
        assertEquals(CourseMark(0, 0), markLatest(emptyList()))
    }

    @Test
    fun theLeadIsMeasuredByTheOdometerWhereThereIsOne() {
        // GPS says 20 m a step, the wheels 50 m: 500 m back is 10 fixes, not 25.
        val points = drive(100, 20.0).mapIndexed { i, p -> p.copy(vehicleDistanceM = i * 50.0) }
        assertEquals(CourseMark(99, 89), markLatest(points))
    }

    @Test
    fun recentDriveStaysOutOfTheCourseListUntilKept() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        assertNull(repository.recent())
        val drive = drive(50, 20.0, odometer = true)
        repository.saveRecent(drive, emptyList())

        val recent = repository.recent()!!
        assertEquals(RECENT_NAME, recent.name)
        assertTrue(repository.list().isEmpty())
        val loaded = repository.load(recent).points
        assertEquals(drive.size, loaded.size)
        drive.zip(loaded).forEach { (a, b) -> assertTrue(a.distanceTo(b) < 0.05) }

        // The next drive replaces it.
        repository.saveRecent(drive.take(10), emptyList())
        assertEquals(10, repository.load(repository.recent()!!).points.size)

        val kept = repository.keepRecent()
        assertEquals(listOf(kept.file), repository.list().map { it.file })
        assertTrue(kept.name.startsWith("코스 "))
        assertEquals(10, repository.load(kept).points.size)
        // Kept once: the button pressed again must not add the same drive a second time.
        assertNull(repository.recent())
    }

    @Test
    fun savingTwiceInOneMinuteKeepsBothCourses() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val first = repository.save(drive(5, 20.0), emptyList(), "코스 2026-11-04 16.07")
        val second = repository.save(drive(9, 20.0), emptyList(), "코스 2026-11-04 16.07")
        assertTrue(first.file != second.file)
        assertEquals(2, repository.list().size)
        assertEquals(5, repository.load(first).points.size)
        assertEquals(9, repository.load(second).points.size)
    }

    @Test
    fun renameMovesTheFileAndTrackNameButKeepsTheDateAndFavorite() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val course = repository.save(drive(5, 20.0, odometer = true), emptyList(), "코스 2026-11-04 16.07")
        course.file.setLastModified(1_700_000_000_000L)
        repository.setFavorite(course, true)

        val renamed = repository.rename(course, "  북악 <업힐>  ")
        assertEquals("북악 <업힐>", renamed.name)
        assertTrue(!course.file.exists())
        val listed = repository.list().single()
        assertEquals(renamed.file, listed.file)
        assertEquals(1_700_000_000_000L, listed.modifiedMillis)
        assertTrue(listed.isFavorite)
        val track = repository.load(listed)
        assertEquals("북악 <업힐>", track.name)
        assertEquals(5, track.points.size)
        assertEquals(80.0, track.points.last().vehicleDistanceM!!, 0.01)
    }

    @Test
    fun renameRefusesANameAnotherCourseHas() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        val a = repository.save(drive(5, 20.0), emptyList(), "A")
        repository.save(drive(5, 20.0), emptyList(), "B")
        try {
            repository.rename(a, "B")
            throw AssertionError("renamed over another course")
        } catch (expected: FileAlreadyExistsException) {
        }
        assertEquals(2, repository.list().size)
        assertEquals(5, repository.load(a).points.size)
    }

    @Test(expected = IOException::class)
    fun keepingWithoutARecentDriveFails() {
        CourseRepository(temporaryFolder.newFolder()).keepRecent()
    }
}
