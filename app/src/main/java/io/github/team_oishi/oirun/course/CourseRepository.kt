package io.github.team_oishi.oirun.course

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class SavedCourse(val file: File, val name: String, val modifiedMillis: Long, val isFavorite: Boolean = false, val distanceM: Double? = null)

/**
 * Courses are plain GPX files in the app's external files dir, so they can be pulled with
 * `adb pull /sdcard/Android/data/<package>/files/courses` and opened in Strava or a watch app.
 */
class CourseRepository internal constructor(private val dir: File) {
    private data class DistanceKey(val path: String, val modified: Long, val bytes: Long)
    private data class DistanceResult(val meters: Double?)
    private val distances = ConcurrentHashMap<DistanceKey, DistanceResult>()

    private fun distanceKey(file: File) = DistanceKey(file.path, file.lastModified(), file.length())

    fun hasDistance(course: SavedCourse): Boolean = distances.containsKey(distanceKey(course.file))

    /** Run on IO; cache even unreadable files to avoid retrying on every list refresh. */
    fun cacheDistance(course: SavedCourse) {
        val key = distanceKey(course.file)
        distances.computeIfAbsent(key) {
            DistanceResult(runCatching { CourseAnalyzer().analyze(load(course).points).totalDistanceM }.getOrNull())
        }
    }

    constructor(context: Context) : this(File(context.getExternalFilesDir(null) ?: context.filesDir, "courses"))

    init {
        dir.mkdirs()
    }

    /** Run on IO. Update only unchanged earlier samples, preserving edits and deletions. */
    fun installSamples() {
        val marker = File(dir, ".samples-v3-installed")
        if (marker.exists()) return
        val upgrading = File(dir, ".samples-v1-installed").exists() || File(dir, ".samples-v2-installed").exists()
        // v1 had approximate coordinates, v2 a 0 m placeholder altitude; v3 has terrain altitude.
        val legacyHashes = listOf("legacy-v1.sha256", "legacy-v2.sha256").flatMap { resource ->
            checkNotNull(javaClass.getResourceAsStream("/sample-courses/$resource")).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val (hash, name) = line.split("  ", limit = 2)
                    name to hash
                }.toList()
            }
        }.groupBy({ it.first }, { it.second })
        SampleCourses.all.forEachIndexed { index, sample ->
            val file = File(dir, "${sample.name}.gpx")
            val replaceLegacy = file.exists() && sha256(file) in legacyHashes[file.name].orEmpty()
            // A missing file after an earlier completed installation was deleted by the user.
            if (replaceLegacy || (!file.exists() && !upgrading)) {
                val partial = File(dir, "${file.name}.part")
                try {
                    sample.openGpx().use { input ->
                        partial.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!partial.renameTo(file)) throw IOException("Could not install ${file.name}")
                    // Stable distance/route order, below newly recorded courses.
                    file.setLastModified(1_767_225_600_000L - index)
                } finally {
                    partial.delete()
                }
            }
        }
        // A failed installation is retried next launch, without overwriting completed files.
        marker.writeText("3\n")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun list(): List<SavedCourse> =
        dir.listFiles { f -> f.extension == "gpx" }.orEmpty()
            .map { SavedCourse(it, it.nameWithoutExtension, it.lastModified(), favoriteMarker(it).exists(), distances[distanceKey(it)]?.meters) }
            .sortedWith(compareByDescending<SavedCourse> { it.isFavorite }.thenByDescending { it.modifiedMillis }.thenBy { it.file.name })

    /** Never replaces a course: a name already taken (two saves in one minute) gets a number. */
    fun save(points: List<TrackPoint>, stops: List<Stop>, name: String = defaultName(), memos: List<Memo> = emptyList()): SavedCourse {
        val (file, unique) = generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) name else "$name ($n)" }
            .map { fileFor(it) to it }
            .first { !it.first.exists() }
        file.bufferedWriter().use { Gpx.write(unique, points, it, stops, memos) }
        return SavedCourse(file, unique, file.lastModified(), favoriteMarker(file).exists())
    }

    /**
     * Renames the file and the track name inside it, keeping the recorded date (the list's
     * order) and the favorite. Throws if another course already has the name.
     */
    fun rename(course: SavedCourse, name: String): SavedCourse {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Blank course name" }
        val target = fileFor(trimmed)
        if (target != course.file && target.exists()) throw FileAlreadyExistsException(target)
        val modified = course.file.lastModified()
        // Only the first track name changes, so samples and imported files keep everything else.
        val text = course.file.readText().let { gpx ->
            TRACK_NAME.find(gpx)?.let { gpx.replaceRange(it.range, "${it.groupValues[1]}${Gpx.escape(trimmed)}${it.groupValues[2]}") } ?: gpx
        }
        val partial = File(target.path + ".part")
        try {
            partial.writeText(text)
            partial.setLastModified(modified)
            if (!partial.renameTo(target)) throw IOException("Could not rename ${course.file.name}")
            if (target != course.file) course.file.delete()
        } finally {
            partial.delete()
        }
        val favorite = favoriteMarker(course.file)
        if (target != course.file && favorite.exists()) favorite.renameTo(favoriteMarker(target))
        return SavedCourse(target, trimmed, target.lastModified(), favoriteMarker(target).exists(), course.distanceM)
    }

    private fun fileFor(name: String) = File(dir, "${name.replace(Regex("[^\\w가-힣 _-]"), "_")}.gpx")

    fun load(course: SavedCourse): GpxTrack = course.file.inputStream().use(Gpx::read)

    /**
     * The whole of the last drive, kept until the next one so a road noticed too late can still
     * be saved. Outside the course list; [keepRecent] moves it in.
     */
    fun saveRecent(points: List<TrackPoint>, stops: List<Stop>, memos: List<Memo> = emptyList()) {
        recentFile.parentFile?.mkdirs()
        val partial = File(recentFile.path + ".part")
        partial.bufferedWriter().use { Gpx.write(RECENT_NAME, points, it, stops, memos) }
        if (!partial.renameTo(recentFile)) throw IOException("Could not keep the recent drive")
    }

    fun recent(): SavedCourse? = recentFile.takeIf { it.isFile }?.let { SavedCourse(it, RECENT_NAME, it.lastModified()) }

    /**
     * Saves the recent drive as a course named after when it ended. It then stops being the
     * recent drive, so the same drive can't be saved twice.
     */
    fun keepRecent(): SavedCourse {
        val recent = recent() ?: throw IOException("No recent drive")
        val track = load(recent)
        val kept = save(track.points, track.stops, defaultName(LocalDateTime.ofInstant(Instant.ofEpochMilli(recent.modifiedMillis), ZoneId.systemDefault())), track.memos)
        recent.file.delete()
        return kept
    }

    private val recentFile get() = File(File(dir, ".recent"), "latest.gpx")

    fun delete(course: SavedCourse) {
        if (course.file.exists() && !course.file.delete()) throw IOException("Could not delete ${course.file.name}")
        favoriteMarker(course.file).delete()
    }

    /** Keep metadata separate so favoriting never changes the GPX or its recorded date. */
    fun setFavorite(course: SavedCourse, favorite: Boolean) {
        if (!course.file.isFile) throw IOException("Course no longer exists: ${course.file.name}")
        val marker = favoriteMarker(course.file)
        if (favorite) {
            marker.parentFile?.mkdirs()
            if (!marker.exists() && !marker.createNewFile()) throw IOException("Could not favorite ${course.file.name}")
        } else if (marker.exists() && !marker.delete()) {
            throw IOException("Could not unfavorite ${course.file.name}")
        }
    }

    private fun favoriteMarker(file: File) = File(File(dir, ".favorites"), file.name)

    private fun defaultName(at: LocalDateTime = LocalDateTime.now()) =
        "코스 " + at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))
}

const val RECENT_NAME = "최근 주행"

private val TRACK_NAME = Regex("""(<trk>\s*<name>)[^<]*(</name>)""")
