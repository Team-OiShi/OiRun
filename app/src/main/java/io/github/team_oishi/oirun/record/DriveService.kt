package io.github.team_oishi.oirun.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.team_oishi.oirun.CourseActivity
import io.github.team_oishi.oirun.R
import io.github.team_oishi.oirun.OiRunApp
import io.github.team_oishi.oirun.course.CourseAnalyzer
import io.github.team_oishi.oirun.course.CourseRepository
import io.github.team_oishi.oirun.course.DEFAULT_LEAD_M
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.location.GearPosition
import io.github.team_oishi.oirun.location.VehicleGear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Keeps the app alive in the background and drives the [Recorder] by the gear: leaving park
 * starts recording the drive, and parking ends it at once and brings up the course list. Only a part the driver marked (the app icon while driving, see MainActivity) is saved as
 * a course, with a notification that opens it; the rest is kept as the recent drive until the
 * next one.
 * Recordings started from the screen run here too, so they survive the screen being closed,
 * but only parking ends the ones the gear started.
 */
class DriveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as OiRunApp
    private val recorder get() = app.recorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing(Phase.of(recorder.state.value)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        scope.launch {
            recorder.state.map(Phase::of).distinctUntilChanged().collect {
                NotificationManagerCompat.from(this@DriveService).notifyIfAllowed(ONGOING_ID, ongoing(it))
            }
        }
        scope.launch { followGear() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun followGear() {
        try {
            VehicleGear(this).positions().collectLatest { position ->
                Log.i(TAG, "Gear: $position")
                app.gear.value = position
                val state = recorder.state.value
                when (position) {
                    GearPosition.UNKNOWN -> Unit
                    GearPosition.IN_GEAR -> if (state is RecorderState.Idle && canRecord()) recorder.start(auto = true)
                    // Leaving park again mid-save must not cut the save short.
                    GearPosition.PARK -> if (state is RecorderState.Recording && state.auto) {
                        withContext(NonCancellable) { recorder.finishDrive()?.let { parked(it) } }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.gear.value = GearPosition.UNKNOWN
            Log.w(TAG, "No gear selection; recording only from the screen", e)
        }
    }

    private fun canRecord(): Boolean {
        val source = recorder.source ?: return false
        return source.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** The drive is over: say what became of the marked course and show the list, with it selected. */
    private suspend fun parked(result: CourseResult) {
        when (result) {
            is CourseResult.Saved -> notifySaved(result.course)
            is CourseResult.TooShort -> notifyNotSaved(result.message.orEmpty())
            CourseResult.None -> Unit
        }
        val list = Intent(this, CourseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        result.saved?.let { list.putExtra(CourseActivity.EXTRA_COURSE_PATH, it.file.path) }
        try {
            startActivity(list)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't bring up the course list", e)
        }
    }

    private fun notifyNotSaved(text: String) {
        val notification = NotificationCompat.Builder(this, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("코스를 저장하지 않았습니다")
            .setContentText(text)
            .setContentIntent(openCourse(null))
            .setAutoCancel(true)
            .setCategory(PLEOS_CONNECT_CATEGORY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notifyIfAllowed(NOT_SAVED_ID, notification)
    }

    private suspend fun notifySaved(course: SavedCourse) {
        val analysis = withContext(Dispatchers.IO) {
            val points = CourseRepository(this@DriveService).load(course).points
            CourseAnalyzer().analyze(points)
        }
        val text = String.format(
            Locale.KOREA, "%.2f km · 오르막 %d m · 내리막 %d m",
            analysis.totalDistanceM / 1000, analysis.ascentM.toInt(), analysis.descentM.toInt(),
        )
        val notification = NotificationCompat.Builder(this, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("코스를 저장했습니다 · ${course.name}")
            .setContentText(text)
            .setContentIntent(openCourse(course))
            .setAutoCancel(true)
            // Pleos pops up a third-party app's notification only in these categories
            // (navigation, call, msg, connect.system); any other one only lands in the list.
            .setCategory(PLEOS_CONNECT_CATEGORY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notifyIfAllowed(course.file.name.hashCode(), notification)
    }

    private enum class Phase {
        IDLE, DRIVING, MARKED;

        companion object {
            fun of(state: RecorderState) = when {
                state !is RecorderState.Recording -> IDLE
                state.mark == null -> DRIVING
                else -> MARKED
            }
        }
    }

    private fun ongoing(phase: Phase): Notification {
        val text = when (phase) {
            Phase.IDLE -> "주행을 시작하면 기록을 준비합니다"
            Phase.DRIVING -> "주행 기록 중 · 앱 아이콘을 누르면 코스 기록을 시작합니다"
            Phase.MARKED -> "코스 기록 중"
        }
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openCourse(null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openCourse(course: SavedCourse?): PendingIntent {
        val intent = Intent(this, CourseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        course?.let { intent.putExtra(CourseActivity.EXTRA_COURSE_PATH, it.file.path) }
        return PendingIntent.getActivity(
            this, course?.file?.name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "주행 감지", NotificationManager.IMPORTANCE_LOW)
        )
        // High importance so the car shows it as a heads-up over the navigation, not only in the list.
        manager.createNotificationChannel(
            NotificationChannel(SAVED_CHANNEL, "코스 저장", NotificationManager.IMPORTANCE_HIGH)
        )
        // Channel importance can't be raised once created, so the default-importance one is replaced.
        manager.deleteNotificationChannel("saved")
    }

    /** Notifications need a runtime grant from Android 13; without it they're silently skipped. */
    private fun NotificationManagerCompat.notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this@DriveService, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        notify(id, notification)
    }

    companion object {
        /** Confirms a mark made from the app icon, where no screen opens to show it. */
        fun notifyMarked(context: Context) {
            val open = PendingIntent.getActivity(
                context, MARKED_ID, Intent(context, CourseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, SAVED_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("코스 기록을 시작합니다")
                .setContentText("${DEFAULT_LEAD_M.toInt()} m 전부터 포함 · 앱 아이콘을 다시 누르면 지금까지의 거리와 고저차를 봅니다")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(MARKED_TIMEOUT_MS)
                .setCategory(PLEOS_CONNECT_CATEGORY)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            NotificationManagerCompat.from(context).notify(MARKED_ID, notification)
        }

        /** Shows a memo Gleo added was kept, as Gleo's spoken answer is easy to miss. */
        fun notifyMemo(context: Context, title: String, text: String) {
            val notification = NotificationCompat.Builder(context, SAVED_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("메모를 저장했습니다 · $title")
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(MARKED_TIMEOUT_MS)
                .setCategory(PLEOS_CONNECT_CATEGORY)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            NotificationManagerCompat.from(context).notify(MEMO_ID, notification)
        }

        /** Safe to call repeatedly; only the first start creates the service. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background outside boot or an app update.
                Log.w(TAG, "Couldn't start the drive service", e)
            }
        }
    }
}

private const val TAG = "OiRun"
private const val ONGOING_ID = 1
private const val MARKED_ID = 2
private const val NOT_SAVED_ID = 3
private const val MEMO_ID = 4
private const val MARKED_TIMEOUT_MS = 10_000L
private const val ONGOING_CHANNEL = "drive"
private const val SAVED_CHANNEL = "saved_course"
private const val PLEOS_CONNECT_CATEGORY = "connect.system"
