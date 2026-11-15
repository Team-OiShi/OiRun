package io.github.team_oishi.oirun.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [DriveService] when the head unit boots (and after an app update), so a drive is
 * recorded without opening the app. Boot is one of the few moments Android still lets an app
 * start a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DriveService.start(context)
        }
    }
}
