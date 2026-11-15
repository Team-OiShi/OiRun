package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.AltitudeSource
import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.CourseType
import io.github.team_oishi.oirun.course.DistanceSource
import io.github.team_oishi.oirun.course.Gpx
import io.github.team_oishi.oirun.course.SampleCourse
import io.github.team_oishi.oirun.course.SampleCourses
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.course.distanceTo
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleCoursesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun SampleCourse.track() = openGpx().use(Gpx::read)

    @Test
    fun fiveSamplesForEachOfTheFiveDistances() {
        assertEquals(listOf(3_000.0, 5_000.0, 10_000.0, 21_097.5, 42_195.0), CourseType.entries.map { it.distanceM })
        assertEquals(25, SampleCourses.all.size)
        assertEquals(25, SampleCourses.all.map { it.name }.toSet().size)
        assertEquals(25, SampleCourses.all.map { it.resourceName }.toSet().size)
        CourseType.entries.forEach { type ->
            val samples = SampleCourses.all.filter { it.type == type }
            assertEquals(5, samples.size)
            assertEquals(5, samples.map { it.routeName }.toSet().size)
            assertEquals(5, samples.map { it.track().points.first() }.toSet().size)
        }
    }

    @Test
    fun everyGpxHasContinuousGeometryAndTheRequestedGpsDistance() {
        SampleCourses.all.forEach { sample ->
            val loaded = sample.track()
            assertEquals(sample.name, loaded.name)
            assertTrue(loaded.points.size > 100)
            assertEquals(0.0, loaded.points.first().distanceTo(loaded.points.last()), 0.01)
            loaded.points.forEach { point ->
                assertTrue(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
                assertTrue(point.altitude.isFinite())
                assertEquals(null, point.vehicleDistanceM)
            }
            loaded.points.zipWithNext { a, b ->
                assertTrue("${sample.name}: GPS jump", a.distanceTo(b) in 0.001..25.1)
                assertTrue("${sample.name}: time must advance", b.timeMillis > a.timeMillis)
            }
            val gpsLength = loaded.points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
            assertEquals(sample.name, sample.type.distanceM, gpsLength, 0.5)
            val result = CourseAnalyzer().analyze(loaded.points)
            assertFalse(result.isEmpty)
            assertEquals(DistanceSource.GPS, result.distanceSource)
            // The analyzer keeps samples at 10 m intervals and may trim a tail of <=5 m.
            assertEquals(sample.name, sample.type.distanceM, result.totalDistanceM, 6.0)
            val expectedSplits = when (sample.type) {
                CourseType.KM_3 -> 3
                CourseType.KM_5 -> 5
                CourseType.KM_10 -> 10
                CourseType.HALF -> 22
                CourseType.FULL -> 43
            }
            assertEquals(sample.name, expectedSplits, result.splits.size)
        }
    }

    @Test
    fun allInstalledSamplesKeepTheirRecordedAltitudeAfterSavingAndReopening() {
        val repository = CourseRepository(temporaryFolder.newFolder())
        repository.installSamples()
        repository.list().forEach { course ->
            val track = repository.load(course)
            // Smoothed terrain elevation: riverside paths in Seoul,
            // so a few to a few tens of meters above sea level with only gentle grades.
            assertTrue(course.name, track.points.all { it.altitude in 1.0..100.0 })
            val result = CourseAnalyzer().analyze(track.points, stops = track.stops)
            assertFalse(course.name, result.isEmpty)
            assertEquals(course.name, AltitudeSource.GPS, result.altitudeSource)
            assertTrue(course.name, result.maxAltitudeM > result.minAltitudeM)
            assertTrue(course.name, result.segments.all { kotlin.math.abs(it.gradePercent) < 8.0 })

            val saved = repository.save(track.points, track.stops, "재저장 ${course.name}")
            val reopened = repository.load(saved)
            assertEquals(course.name, track.points.map { it.altitude }, reopened.points.map { it.altitude })
            assertEquals(course.name, result, CourseAnalyzer().analyze(reopened.points, stops = reopened.stops))
        }
    }

    @Test
    fun installationIsPersistentAndDoesNotRestoreDeletedSamples() {
        val dir = temporaryFolder.newFolder()
        val repository = CourseRepository(dir)
        val recording = repository.save(listOf(TrackPoint(37.5, 127.0, 20.0, 0L)), emptyList(), "내 기록")
        val original = recording.file.readBytes()

        repository.installSamples()
        assertEquals(26, repository.list().size)
        assertTrue(original.contentEquals(recording.file.readBytes()))
        assertEquals("내 기록", repository.list().first().name)
        val samples = repository.list().filter { it.name.startsWith("샘플 ") }
        assertEquals(SampleCourses.all.map { it.name }, samples.map { it.name })
        samples.forEach { assertEquals(it.name, repository.load(it).name) }
        SampleCourses.all.zip(samples).forEach { (bundled, installed) ->
            assertArrayEquals(bundled.openGpx().use { it.readBytes() }, installed.file.readBytes())
        }

        CourseRepository(dir).installSamples()
        assertEquals(26, repository.list().size)
        repository.delete(samples.first())
        CourseRepository(dir).installSamples()
        assertEquals(25, repository.list().size)
        assertFalse(samples.first().file.exists())
    }

    @Test
    fun incompleteInstallationPreservesExistingFilesAndRetriesPartialFiles() {
        val dir = temporaryFolder.newFolder()
        val existing = File(dir, "${SampleCourses.all.first().name}.gpx")
        existing.writeText("existing file must be preserved")
        val partial = File(dir, "${SampleCourses.all[1].name}.gpx.part")
        partial.writeText("incomplete GPX")

        val repository = CourseRepository(dir)
        repository.installSamples()
        assertEquals(25, repository.list().size)
        assertEquals("existing file must be preserved", existing.readText())
        assertFalse(partial.exists())
        val completed = repository.list().single { it.name == SampleCourses.all[1].name }
        assertEquals(completed.name, repository.load(completed).name)
    }

    @Test
    fun v1UpgradeCorrectsOnlyUnmodifiedSamplesAndPreservesDeletions() {
        val dir = temporaryFolder.newFolder()
        File(dir, ".samples-v1-installed").writeText("1\n")
        val legacy = File(dir, "샘플 3km 여의도 한강.gpx")
        legacy.writeBytes(checkNotNull(javaClass.getResourceAsStream("/sample-courses/legacy-yeouido-3km.gpx")).use { it.readBytes() })
        val oldBytes = legacy.readBytes()
        val edited = File(dir, "샘플 3km 반포 한강.gpx").apply { writeText("user edited GPX") }
        val repository = CourseRepository(dir)
        val recording = repository.save(listOf(TrackPoint(37.5, 127.0, 20.0, 0L)), emptyList(), "내 기록")
        val recordingBytes = recording.file.readBytes()

        repository.installSamples()

        assertEquals(3, repository.list().size) // The other 23 deleted samples stay deleted.
        assertFalse(oldBytes.contentEquals(legacy.readBytes()))
        assertArrayEquals(SampleCourses.all.first().openGpx().use { it.readBytes() }, legacy.readBytes())
        val corrected = legacy.inputStream().use(Gpx::read)
        assertEquals(SampleCourses.all.first().name, corrected.name)
        assertEquals(0.0, corrected.points.first().distanceTo(SampleCourses.all.first().track().points.first()), 0.01)
        assertEquals("user edited GPX", edited.readText())
        assertTrue(recordingBytes.contentEquals(recording.file.readBytes()))
        assertTrue(File(dir, ".samples-v3-installed").exists())

        val correctedBytes = legacy.readBytes()
        CourseRepository(dir).installSamples()
        assertTrue(correctedBytes.contentEquals(legacy.readBytes()))
        assertEquals(3, repository.list().size)
    }

    @Test
    fun v2UpgradeAddsTerrainAltitudeOnlyToUnmodifiedSamples() {
        val dir = temporaryFolder.newFolder()
        File(dir, ".samples-v2-installed").writeText("2\n")
        val placeholder = File(dir, "샘플 3km 여의도 한강.gpx")
        placeholder.writeBytes(checkNotNull(javaClass.getResourceAsStream("/sample-courses/legacy-v2-yeouido-3km.gpx")).use { it.readBytes() })
        assertTrue(placeholder.inputStream().use(Gpx::read).points.all { it.altitude == 0.0 })
        val edited = File(dir, "샘플 3km 반포 한강.gpx").apply { writeText("user edited GPX") }

        CourseRepository(dir).installSamples()

        assertArrayEquals(SampleCourses.all.first().openGpx().use { it.readBytes() }, placeholder.readBytes())
        assertTrue(placeholder.inputStream().use(Gpx::read).points.all { it.altitude > 0.0 })
        assertEquals("user edited GPX", edited.readText())
        assertEquals(2, CourseRepository(dir).list().size) // Deleted v2 samples stay deleted.
        assertTrue(File(dir, ".samples-v3-installed").exists())
    }

    @Test
    fun routesBeginOnTheMappedRiversidePaths() {
        // Independently captured OSM path nodes, including the two former major offsets.
        val starts = listOf(
            TrackPoint(37.5280973, 126.9322710, 0.0, 0L),
            TrackPoint(37.5104051, 126.9959347, 0.0, 0L),
            TrackPoint(37.5293118, 127.0661120, 0.0, 0L),
            TrackPoint(37.5177794, 127.0668939, 0.0, 0L),
            TrackPoint(37.5533707, 127.0435986, 0.0, 0L),
        )
        SampleCourses.all.take(5).zip(starts).forEach { (sample, expected) ->
            assertTrue(sample.name, sample.track().points.first().distanceTo(expected) < 2.0)
        }
    }
}
