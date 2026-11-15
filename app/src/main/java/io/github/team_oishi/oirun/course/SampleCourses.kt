package io.github.team_oishi.oirun.course

import java.io.InputStream

enum class CourseType(val id: String, val label: String, val distanceM: Double) {
    KM_3("3km", "3km", 3_000.0),
    KM_5("5km", "5km", 5_000.0),
    KM_10("10km", "10km", 10_000.0),
    HALF("half", "하프 21.0975km", 21_097.5),
    FULL("full", "풀 42.195km", 42_195.0),
}

data class SampleCourse(
    val type: CourseType,
    val routeName: String,
    private val routeId: String,
) {
    val name: String get() = "샘플 ${type.label} $routeName"
    val resourceName: String get() = "${type.id}-$routeId.gpx"

    /** Already generated and bundled; installation copies these bytes without parsing. */
    fun openGpx(): InputStream = checkNotNull(
        SampleCourse::class.java.getResourceAsStream("/sample-courses/$resourceName")
    ) { "Missing sample GPX: $resourceName" }
}

/** Metadata for the 25 pre-generated GPX resources bundled with the app. */
object SampleCourses {
    val all: List<SampleCourse> = CourseType.entries.flatMap { type ->
        listOf(
            SampleCourse(type, "여의도 한강", "yeouido"),
            SampleCourse(type, "반포 한강", "banpo"),
            SampleCourse(type, "뚝섬 한강", "ttukseom"),
            SampleCourse(type, "탄천", "tancheon"),
            SampleCourse(type, "중랑천", "jungnangcheon"),
        )
    }
}
