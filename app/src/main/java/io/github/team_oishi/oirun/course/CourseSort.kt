package io.github.team_oishi.oirun.course

import java.text.Collator
import java.util.Locale

enum class CourseSort(val title: String) { TIME("등록 순"), DISTANCE("총 길이"), NAME("이름") }

fun List<SavedCourse>.sortedCourses(sort: CourseSort, descending: Boolean): List<SavedCourse> {
    val valueOrder = when (sort) {
        CourseSort.TIME -> compareBy<SavedCourse> { it.modifiedMillis }
        CourseSort.DISTANCE -> compareBy<SavedCourse> { it.distanceM ?: 0.0 }
        CourseSort.NAME -> {
            val collator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.SECONDARY }
            Comparator<SavedCourse> { a, b -> collator.compare(a.name, b.name) }
        }
    }.let { if (descending) it.reversed() else it }
    // Direction only applies within each group; unknown distances always stay last.
    return sortedWith(
        compareByDescending<SavedCourse> { it.isFavorite }
            .thenBy { sort == CourseSort.DISTANCE && it.distanceM == null }
            .then(valueOrder)
            .thenByDescending { it.modifiedMillis }
            .thenBy { it.file.name }
    )
}
