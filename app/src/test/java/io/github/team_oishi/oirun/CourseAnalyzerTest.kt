package io.github.team_oishi.oirun

import io.github.team_oishi.oirun.course.AltitudeSource
import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.DistanceSource
import io.github.team_oishi.oirun.course.ElevationLookup
import io.github.team_oishi.oirun.course.Stop
import io.github.team_oishi.oirun.course.StopDetector
import io.github.team_oishi.oirun.course.GradeBand
import io.github.team_oishi.oirun.course.Gpx
import io.github.team_oishi.oirun.course.ListenStart
import io.github.team_oishi.oirun.course.Memo
import io.github.team_oishi.oirun.course.TerrainTile
import io.github.team_oishi.oirun.course.Terrarium
import io.github.team_oishi.oirun.course.TileKey
import io.github.team_oishi.oirun.course.TrackPoint
import io.github.team_oishi.oirun.location.TripDistance
import java.io.StringWriter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseAnalyzerTest {

    /** Due-north track: 1° of latitude ≈ 111.195 km. */
    private fun track(lengthM: Double, stepM: Double = 5.0, altitude: (Double) -> Double): List<TrackPoint> {
        val n = (lengthM / stepM).toInt()
        return (0..n).map { i ->
            val d = i * stepM
            TrackPoint(37.5 + d / 111_195.0, 127.0, altitude(d), i * 1000L)
        }
    }

    @Test
    fun steadyClimbReadsAsItsGrade() {
        val result = CourseAnalyzer().analyze(track(2000.0) { 100 + it * 0.05 })

        assertEquals(2000.0, result.totalDistanceM, 5.0)
        assertEquals(100.0, result.ascentM, 3.0)
        assertEquals(0.0, result.descentM, 0.01)
        // Inner segments; the smoothing window bends the two ends.
        result.segments.drop(2).dropLast(2).forEach { assertEquals(5.0, it.gradePercent, 0.1) }
        assertEquals(2, result.splits.size)
        result.splits.forEach { assertEquals(5.0, it.avgGradePercent, 0.6) }
    }

    @Test
    fun altitudeNoiseDoesNotInventClimbing() {
        val rnd = Random(7)
        val result = CourseAnalyzer().analyze(track(3000.0) { 50 + rnd.nextDouble(-3.0, 3.0) })

        assertTrue("ascent ${result.ascentM}", result.ascentM < 15)
        val gentle = setOf(GradeBand.GENTLE_DOWN, GradeBand.FLAT, GradeBand.GENTLE_UP)
        val share = result.segments.count { GradeBand.of(it.gradePercent) in gentle }.toDouble() / result.segments.size
        assertTrue("gentle share $share", share > 0.9)
    }

    @Test
    fun stopsAtLightsAddNoDistance() {
        val moving = track(500.0) { 10.0 }
        val stopped = moving.take(50) + List(30) { moving[49].copy(timeMillis = 50_000L + it) } + moving.drop(50)

        assertEquals(500.0, CourseAnalyzer().analyze(stopped).totalDistanceM, 2.0)
    }

    /** The odometer as it often reports: in [tickM] steps, attached to the fixes after each tick. */
    private fun withOdometer(points: List<TrackPoint>, trueStepM: Double, tickM: Double = 100.0) =
        points.mapIndexed { i, p -> p.copy(vehicleDistanceM = Math.floor(i * trueStepM / tickM) * tickM) }

    @Test
    fun odometerSetsTheDistanceOverGps() {
        // GPS reads the 5 m steps 4 % long; the car's wheels say 4.8 m.
        val points = withOdometer(track(2000.0) { 100 + it * 0.05 }, trueStepM = 4.8)
        val result = CourseAnalyzer().analyze(points)

        assertEquals(DistanceSource.VEHICLE, result.distanceSource)
        assertEquals(2000.0, result.gpsDistanceM, 5.0)
        assertEquals(400 * 4.8, result.totalDistanceM, 5.0)
        // The climb is unchanged, so over the shorter distance the grade is steeper. The 100 m
        // ticks land a few meters late, which wobbles single 50 m segments a little.
        val inner = result.segments.drop(2).dropLast(2)
        assertEquals(5.0 / 0.96, inner.map { it.gradePercent }.average(), 0.05)
        inner.forEach { assertEquals(5.0 / 0.96, it.gradePercent, 0.3) }
    }

    @Test
    fun odometerAbsorbsAGpsJump() {
        val points = withOdometer(track(1000.0) { 10.0 }, trueStepM = 5.0).toMutableList()
        // One fix lands 3 km off and the next comes back.
        points[100] = points[100].copy(latitude = points[100].latitude + 3000 / 111_195.0)
        val result = CourseAnalyzer().analyze(points)

        assertTrue("gps ${result.gpsDistanceM}", result.gpsDistanceM > 6000)
        assertEquals(1000.0, result.totalDistanceM, 10.0)
    }

    @Test
    fun staleFirstOdometerReadingIsIgnored() {
        // Seen on the emulator: the first reading was the service's cached value, and the
        // next one, a second later and with the car still parked, was 1044 m on.
        val points = withOdometer(track(2000.0) { 10.0 }, trueStepM = 5.0)
            .map { it.copy(vehicleDistanceM = it.vehicleDistanceM!! + if (it.timeMillis > 0) 1044.0 else 0.0) }
        val result = CourseAnalyzer().analyze(points)

        assertEquals(DistanceSource.VEHICLE, result.distanceSource)
        assertEquals(2000.0, result.totalDistanceM, 10.0)
    }

    @Test
    fun withoutOdometerGpsMeasures() {
        val result = CourseAnalyzer().analyze(track(500.0) { 10.0 })
        assertEquals(DistanceSource.GPS, result.distanceSource)
        assertEquals(500.0, result.totalDistanceM, 2.0)
    }

    @Test
    fun tripCounterRestartKeepsCounting() {
        val trip = TripDistance()
        listOf(1200.0, 1250.0, 1300.0, 20.0, 70.0).forEach(trip::update)
        assertEquals(170.0, trip.meters, 1e-6)
    }

    /** Ground along the due-north test track, by distance from its start. */
    private fun ground(altitude: (Double) -> Double?) =
        ElevationLookup { lat, _ -> altitude((lat - 37.5) * 111_195.0) }

    @Test
    fun terrainReplacesNoisyGpsAltitude() {
        val rnd = Random(3)
        // GPS sits 25 m high (ellipsoid vs sea level) and jitters ±6 m; the ground climbs 4 %.
        val points = track(3000.0) { 125 + it * 0.04 + rnd.nextDouble(-6.0, 6.0) }
        val result = CourseAnalyzer().analyze(points, ground { 100 + it * 0.04 })

        assertEquals(AltitudeSource.TERRAIN, result.altitudeSource)
        assertEquals(120.0, result.ascentM, 4.0)
        assertEquals(0.0, result.descentM, 0.01)
        assertEquals(100.0, result.minAltitudeM, 3.0)
        result.segments.drop(3).dropLast(3).forEach { assertEquals(4.0, it.gradePercent, 0.2) }
    }

    @Test
    fun bridgeDoesNotFollowTheValleyBelow() {
        // The road crosses a 40 m deep valley on a level bridge from 1000 to 1300 m.
        val points = track(2000.0) { 50.0 }
        val valley = ground { d -> if (d in 1000.0..1300.0) 10.0 else 50.0 }
        val result = CourseAnalyzer().analyze(points, valley)

        assertEquals(AltitudeSource.TERRAIN, result.altitudeSource)
        assertTrue("descent ${result.descentM}", result.descentM < 2)
        assertEquals(50.0, result.minAltitudeM, 1.0)
    }

    @Test
    fun patchyTerrainFallsBackToGps() {
        val points = track(1000.0) { 30.0 }
        val result = CourseAnalyzer().analyze(points, ground { d -> if (d < 500) 30.0 else null })
        assertEquals(AltitudeSource.GPS, result.altitudeSource)
    }

    @Test
    fun terrariumPixels() {
        // 0x80 0x00 0x00 is sea level; each green step is a meter, blue is 1/256 m.
        assertEquals(0f, Terrarium.decode(0xFF800000.toInt()), 0f)
        assertEquals(123.5f, Terrarium.decode(0xFF807B80.toInt()), 0f)
        assertEquals(TileKey(14, 13970, 6343), Terrarium.keyOf(37.59282, 126.966388))

        val key = TileKey(14, 13970, 6343)
        // A west-to-east ramp, one meter per pixel.
        val tile = TerrainTile(key, FloatArray(Terrarium.SIZE * Terrarium.SIZE) { (it % Terrarium.SIZE).toFloat() })
        val n = (1 shl 14).toDouble()
        val lonAt = { px: Double -> (key.x + px / Terrarium.SIZE) / n * 360 - 180 }
        assertEquals(99.5, tile.elevationAt(37.5928, lonAt(100.0)), 1e-6)
        assertEquals(0.0, tile.elevationAt(37.5928, lonAt(0.1)), 1e-6)
    }

    /** 5 m/s with the brake off, then [stopSeconds] at 500 m with the brake on, then on again. */
    private fun withStop(stopSeconds: Int, brakeWhileMoving: Boolean = false, parkAtEnd: Boolean = false): List<TrackPoint> {
        val moving = track(1000.0) { 10.0 }.map { it.copy(brake = brakeWhileMoving) }
        val at = moving[100]
        val stop = List(stopSeconds) { s -> at.copy(timeMillis = at.timeMillis + (s + 1) * 1000L, brake = true) }
        val after = moving.drop(101).map { it.copy(timeMillis = it.timeMillis + stopSeconds * 1000L) }
        val park = if (parkAtEnd) List(60) { s -> after.last().copy(timeMillis = after.last().timeMillis + (s + 1) * 1000L, brake = true) } else emptyList()
        return moving.take(101) + stop + after + park
    }

    @Test
    fun stopAtALightIsFound() {
        val stops = StopDetector().detect(withStop(stopSeconds = 30))
        assertEquals(1, stops.size)
        assertEquals(37.5 + 500 / 111_195.0, stops[0].latitude, 1e-6)

        val analysis = CourseAnalyzer().analyze(withStop(30), stops = stops)
        assertEquals(500.0, analysis.stops.single().distanceM, 10.0)
    }

    @Test
    fun brakingWhileRollingOrBrieflyIsNoStop() {
        assertTrue(StopDetector().detect(withStop(stopSeconds = 0, brakeWhileMoving = true)).isEmpty())
        assertTrue(StopDetector().detect(withStop(stopSeconds = 2)).isEmpty())
    }

    @Test
    fun parkingAtTheEndIsNoStop() {
        assertEquals(1, StopDetector().detect(withStop(stopSeconds = 30, parkAtEnd = true)).size)
    }

    @Test
    fun stopsRoundTripAsWaypoints() {
        val points = track(100.0, stepM = 25.0) { 10.0 }
        val stops = listOf(Stop(37.5004, 127.0))
        val xml = StringWriter().also { Gpx.write("코스", points, it, stops) }.toString()
        val read = Gpx.read(xml.byteInputStream())

        assertTrue(xml, xml.contains("<wpt") && xml.contains("<type>stop</type>"))
        assertEquals("코스", read.name)
        assertEquals(stops, read.stops)
    }

    @Test
    fun memosRoundTripAsWaypointsAndLandOnTheCourse() {
        val points = track(100.0, stepM = 25.0) { 10.0 }
        val memos = listOf(Memo(37.5004, 127.0, "화장실 <공원> & 급수대"))
        val xml = StringWriter().also { Gpx.write("코스", points, it, listOf(Stop(37.5002, 127.0)), memos) }.toString()
        val read = Gpx.read(xml.byteInputStream())

        assertTrue(xml, xml.contains("<type>memo</type>"))
        assertEquals(memos, read.memos)
        assertEquals(1, read.stops.size)
        val placed = CourseAnalyzer().analyze(read.points, memos = read.memos).memos.single()
        assertEquals(44.0, placed.distanceM, 10.0)
    }

    /** Gleo: listening sound, the driver speaks, end sound, then the call. */
    @Test
    fun listenStartWalksBackToTheListeningSound() {
        assertEquals(10_000L, ListenStart.find(listOf(10_000L, 17_000L), calledAt = 19_000L))
        // Gleo asked again: two pairs of sounds, the request still began at the first.
        assertEquals(10_000L, ListenStart.find(listOf(10_000L, 14_000L, 16_000L, 20_000L), calledAt = 22_000L))
        // A Gleo answer from an earlier talk, long before, is another request.
        assertEquals(40_000L, ListenStart.find(listOf(5_000L, 40_000L, 46_000L), calledAt = 48_000L))
        // Never further back than a request can take.
        assertEquals(25_000L, ListenStart.find(listOf(15_000L, 25_000L, 30_000L, 40_000L), calledAt = 52_000L))
        // No sound: the app was called some other way.
        assertEquals(null, ListenStart.find(emptyList(), calledAt = 1_000L))
        assertEquals(null, ListenStart.find(listOf(1_000L), calledAt = 30_000L))
    }

    @Test
    fun tooFewPointsIsEmpty() {
        assertTrue(CourseAnalyzer().analyze(track(0.5) { 0.0 }).isEmpty)
    }

    @Test
    fun gpxRoundTrip() {
        val points = track(100.0, stepM = 25.0) { 12.3 + it / 100 }
        val xml = StringWriter().also { Gpx.write("테스트 & 코스", points, it) }.toString()
        val read = Gpx.read(xml.byteInputStream())

        assertTrue(xml, !xml.contains("<time>"))
        assertEquals("테스트 & 코스", read.name)
        assertEquals(points.size, read.points.size)
        points.zip(read.points).forEach { (a, b) ->
            assertEquals(a.latitude, b.latitude, 1e-6)
            assertEquals(a.altitude, b.altitude, 0.01)
            assertEquals(null, b.vehicleDistanceM)
        }

        val odo = withOdometer(points, trueStepM = 24.0, tickM = 10.0)
        val readOdo = Gpx.read(StringWriter().also { Gpx.write("odo", odo, it) }.toString().byteInputStream())
        assertEquals(odo.map { it.vehicleDistanceM }, readOdo.points.map { it.vehicleDistanceM })
    }
}
