package io.github.team_oishi.oirun

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.team_oishi.oirun.location.GearPosition
import io.github.team_oishi.oirun.record.DriveService
import io.github.team_oishi.oirun.record.RecorderState

/**
 * What the app icon does, like a dashcam's event button. While driving, the first press marks
 * the road as a course without opening anything (a notification confirms it); the next press
 * opens the screen with the distance and climb since the mark. Parked, it opens the app.
 *
 * It shows no window and runs in a task of its own (taskAffinity ""), so every press of the
 * icon reaches it instead of just bringing the app's task back. It keeps the launcher's class
 * name: the Pleos dock remembers the component an icon started, so an update that moved the
 * launcher entry elsewhere would leave the dock opening the screen directly.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveService.start(this)
        val app = application as OiRunApp
        val recording = app.recorder.state.value as? RecorderState.Recording
        val marking = app.gear.value == GearPosition.IN_GEAR && recording != null && recording.auto && recording.mark == null
        if (marking && app.recorder.mark()) {
            Log.i(TAG, "Marked a course from the app icon")
            DriveService.notifyMarked(this)
        } else {
            startActivity(
                Intent(this, CourseActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }
}

private const val TAG = "OiRun"
