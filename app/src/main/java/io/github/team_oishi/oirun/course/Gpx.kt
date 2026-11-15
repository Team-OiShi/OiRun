package io.github.team_oishi.oirun.course

import java.io.InputStream
import java.io.Writer
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Minimal GPX 1.1 track reader/writer: one track, one segment, lat/lon/ele. Times are left out:
 * the drive's timing is the car's pace, and a running app reading it would take the course for
 * an activity at that pace. The reader still takes times from GPX files that have them. The
 * odometer distance rides in a point extension, which other GPX apps skip. Stops and memos are
 * waypoints of type "stop" and "memo", which map apps show as pins; a memo's title is its name and its text its description.
 */
object Gpx {
    private const val EXT_NS = "https://github.com/Team-OiShi/OiRun"
    private const val ODOMETER = "rc:odometer"
    private const val STOP_TYPE = "stop"
    private const val MEMO_TYPE = "memo"

    fun write(name: String, points: List<TrackPoint>, out: Writer, stops: List<Stop> = emptyList(), memos: List<Memo> = emptyList()) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.write("\n<gpx version=\"1.1\" creator=\"OiRun\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:rc=\"$EXT_NS\">\n")
        stops.forEachIndexed { i, stop ->
            out.write(
                "  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>정지 %d</name><type>$STOP_TYPE</type></wpt>\n".format(
                    java.util.Locale.ROOT, stop.latitude, stop.longitude, i + 1,
                )
            )
        }
        for (memo in memos) {
            out.write(
                "  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><desc>%s</desc><type>$MEMO_TYPE</type></wpt>\n".format(
                    java.util.Locale.ROOT, memo.latitude, memo.longitude, escape(memo.title), escape(memo.text),
                )
            )
        }
        out.write("  <trk>\n    <name>${escape(name)}</name>\n    <trkseg>\n")
        for (p in points) {
            val ext = p.vehicleDistanceM?.let { "<extensions><$ODOMETER>%.1f</$ODOMETER></extensions>".format(java.util.Locale.ROOT, it) }
            out.write(
                "      <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.2f</ele>%s</trkpt>\n".format(
                    java.util.Locale.ROOT, p.latitude, p.longitude, p.altitude, ext.orEmpty(),
                )
            )
        }
        out.write("    </trkseg>\n  </trk>\n</gpx>\n")
        out.flush()
    }

    fun read(input: InputStream): GpxTrack {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(input)
        val trk = doc.getElementsByTagName("trk").item(0) as? Element
        val name = trk?.child("name").orEmpty()
        val nodes = doc.getElementsByTagName("trkpt")
        val points = (0 until nodes.length).map { i ->
            val el = nodes.item(i) as Element
            TrackPoint(
                latitude = el.getAttribute("lat").toDouble(),
                longitude = el.getAttribute("lon").toDouble(),
                altitude = el.child("ele")?.toDoubleOrNull() ?: 0.0,
                timeMillis = el.child("time")?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
                vehicleDistanceM = el.child(ODOMETER)?.toDoubleOrNull(),
            )
        }
        val wpts = doc.getElementsByTagName("wpt").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        val stops = wpts.filter { it.child("type") == STOP_TYPE }.map {
            Stop(latitude = it.getAttribute("lat").toDouble(), longitude = it.getAttribute("lon").toDouble())
        }
        val memos = wpts.filter { it.child("type") == MEMO_TYPE }.map {
            val name = it.child("name").orEmpty()
            Memo(it.getAttribute("lat").toDouble(), it.getAttribute("lon").toDouble(), text = it.child("desc") ?: name, title = name)
        }
        return GpxTrack(name, points, stops, memos)
    }

    private fun Element.child(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()

    internal fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

data class GpxTrack(val name: String, val points: List<TrackPoint>, val stops: List<Stop> = emptyList(), val memos: List<Memo> = emptyList())
