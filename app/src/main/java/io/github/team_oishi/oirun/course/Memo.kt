package io.github.team_oishi.oirun.course

/**
 * A note the driver asked Gleo for on the way, kept where they started asking. [title] is a few
 * words for the map: Gleo's, if it fills one in along with the note, or else the note's start.
 */
data class Memo(
    val latitude: Double,
    val longitude: Double,
    val text: String,
    val title: String = shortTitle(text),
)

/** The note's start, when Gleo gave no title. */
fun shortTitle(text: String): String = if (text.length <= 12) text else text.take(11).trimEnd() + "…"

/**
 * When the driver started asking Gleo. Gleo calls the app only after it has heard and parsed
 * the request, and the call carries no time. Gleo plays a sound as it starts listening and
 * another as it stops, both as assistant audio that any app can see start; nothing else of
 * Gleo's plays before the app answers. So walking back from the call through assistant sounds
 * that came close together finds the listening sound.
 */
object ListenStart {
    /** A longer silence between two sounds means the earlier one belongs to another request. */
    const val MAX_GAP_MILLIS = 15_000L
    /** Asking for a memo never takes longer than this. */
    const val MAX_BACK_MILLIS = 30_000L

    /** [soundStarts] and [calledAt] on one clock; null if no sound led up to the call. */
    fun find(soundStarts: List<Long>, calledAt: Long): Long? {
        var found: Long? = null
        var later = calledAt
        for (t in soundStarts.filter { it <= calledAt }.sortedDescending()) {
            if (calledAt - t > MAX_BACK_MILLIS || later - t > MAX_GAP_MILLIS) break
            found = t
            later = t
        }
        return found
    }
}
