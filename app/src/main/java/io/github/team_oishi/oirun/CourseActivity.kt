package io.github.team_oishi.oirun

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.team_oishi.oirun.location.GearPosition
import io.github.team_oishi.oirun.location.VehicleGear
import io.github.team_oishi.oirun.record.DriveService
import io.github.team_oishi.oirun.ui.MainScreen
import io.github.team_oishi.oirun.ui.PleosTheme
import io.github.team_oishi.oirun.ui.OiRunViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The app's screen; the app icon reaches it through [MainActivity]. */
open class CourseActivity : ComponentActivity() {

    private val viewModel: OiRunViewModel by viewModels()
    private var parked by mutableStateOf(false)
    private val fullscreen get() = this is FullscreenActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From here on the service records drives on its own, including after the next boot.
        DriveService.start(this)
        if (savedInstanceState == null) openCourseFrom(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                parked = false
                try {
                    VehicleGear(this@CourseActivity).positions().collect { gear ->
                        val wasParked = parked
                        parked = gear == GearPosition.PARK
                        if (fullscreen && !parked) finishAndRemoveTask()
                        // Back to the navigation when driving off; a screen opened while
                        // driving stays.
                        else if (wasParked && gear == GearPosition.IN_GEAR) finishAndRemoveTask()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("OiRun", "Fullscreen unavailable: no gear state", e)
                    parked = false
                    if (fullscreen) finishAndRemoveTask()
                }
            }
        }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            BackHandler(enabled = fullscreen) { closeFullscreen() }
            val locationPermissions = state.source?.permissions.orEmpty()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (results.values.all { it }) viewModel.startRecording()
                else viewModel.reportMessage("위치 권한이 거부되어 기록할 수 없습니다")
            }
            // Asked up front, since a drive the gear starts has no screen to ask from.
            val backgroundLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }
            LaunchedEffect(Unit) {
                val missing = missing(locationPermissions + notificationPermission())
                if (missing.isNotEmpty()) backgroundLauncher.launch(missing.toTypedArray())
            }

            PleosTheme {
                MainScreen(
                    state = state,
                    onStart = {
                        val missing = missing(locationPermissions)
                        if (missing.isEmpty()) viewModel.startRecording()
                        else permissionLauncher.launch(missing.toTypedArray())
                    },
                    onStop = viewModel::stopRecording,
                    onOpen = viewModel::open,
                    onDelete = viewModel::delete,
                    onUndoDelete = viewModel::undoDelete,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onCourseSort = viewModel::setCourseSort,
                    onSortDirection = viewModel::toggleSortDirection,
                    onDismissMessage = viewModel::dismissMessage,
                    onKeepRecent = viewModel::keepRecent,
                    onRename = viewModel::rename,
                    onShare = viewModel::share,
                    fullscreen = fullscreen,
                    canEnterFullscreen = parked,
                    onToggleFullscreen = {
                        if (fullscreen) closeFullscreen()
                        else if (parked) {
                            startActivity(Intent(this, FullscreenActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                putExtra(EXTRA_COURSE_PATH, state.coursePath)
                            })
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Courses can be added or deleted while the other window is open.
        viewModel.refreshSavedCourses()
    }

    private fun closeFullscreen() {
        startActivity(Intent(this, CourseActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_COURSE_PATH, viewModel.state.value.coursePath)
        })
        finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openCourseFrom(intent)
    }

    /** A saved-course notification names the course to show; otherwise the latest is selected. */
    private fun openCourseFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_COURSE_PATH)?.let(viewModel::open) ?: viewModel.selectLatest()
    }

    private fun missing(permissions: List<String>) = permissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun notificationPermission() =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    companion object {
        const val EXTRA_COURSE_PATH = "course_path"
    }
}
