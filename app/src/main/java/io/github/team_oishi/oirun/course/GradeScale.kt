package io.github.team_oishi.oirun.course

/**
 * Grade buckets shared by the map, the profile chart and the legend.
 * Diverging: blue (downhill) ↔ neutral gray (flat) ↔ red (uphill), validated for CVD on a light surface.
 */
enum class GradeBand(val label: String, val upperPercent: Double, val argb: Long) {
    STEEP_DOWN("-8% 이하", -8.0, 0xFF104281),
    DOWN("-8 ~ -4%", -4.0, 0xFF2A78D6),
    GENTLE_DOWN("-4 ~ -1.5%", -1.5, 0xFF86B6EF),
    FLAT("평지 ±1.5%", 1.5, 0xFFC3C2B7),
    GENTLE_UP("1.5 ~ 4%", 4.0, 0xFFEF9890),
    UP("4 ~ 8%", 8.0, 0xFFE34948),
    STEEP_UP("8% 이상", Double.POSITIVE_INFINITY, 0xFF9B1C1C);

    /** `#RRGGBB` for MapLibre expressions. */
    val hex: String get() = "#%06X".format(argb and 0xFFFFFF)

    companion object {
        fun of(gradePercent: Double): GradeBand = entries.first { gradePercent < it.upperPercent }
    }
}
