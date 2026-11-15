package io.github.team_oishi.oirun

import android.app.Application
import android.util.Log
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.location.GearPosition
import io.github.team_oishi.oirun.record.Recorder
import io.github.team_oishi.oirun.ui.CourseDeletionQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class OiRunApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The gear as DriveService last saw it; the app icon acts on it (see MainActivity). */
    val gear = MutableStateFlow(GearPosition.UNKNOWN)

    /** Shared by the screen and [io.github.team_oishi.oirun.record.DriveService]. */
    val recorder: Recorder by lazy { Recorder(this, appScope) }

    // Both activity windows must share the same undo deadline, even while one is closing.
    internal val courseDeletions: CourseDeletionQueue by lazy {
        CourseDeletionQueue(appScope, CourseRepository(this)::delete) { error ->
            Log.w("OiRun", "Could not delete course", error)
        }
    }
}
