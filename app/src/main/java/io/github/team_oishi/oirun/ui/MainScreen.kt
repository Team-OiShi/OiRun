package io.github.team_oishi.oirun.ui

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.team_oishi.oirun.R
import io.github.team_oishi.oirun.course.AltitudeSource
import io.github.team_oishi.oirun.course.CourseSort
import io.github.team_oishi.oirun.course.DistanceSource
import io.github.team_oishi.oirun.course.GradeBand
import io.github.team_oishi.oirun.course.RECENT_NAME
import io.github.team_oishi.oirun.course.SavedCourse
import io.github.team_oishi.oirun.course.sortedCourses
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Pleos runs apps in landscape and portrait windows (Window Policy), so the panel sits beside
 * the map when the window is wide and above it when it is tall.
 */
@Composable
fun MainScreen(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (SavedCourse) -> Unit,
    onDelete: (SavedCourse) -> Unit,
    onUndoDelete: () -> Unit,
    onToggleFavorite: (SavedCourse) -> Unit,
    onCourseSort: (CourseSort) -> Unit,
    onSortDirection: () -> Unit,
    onDismissMessage: () -> Unit,
    onKeepRecent: () -> Unit,
    onRename: (String) -> Unit,
    onShare: () -> Unit,
    fullscreen: Boolean,
    canEnterFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val c = PleosTheme.colors
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sortDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    // Only a saved course, and only while parked: editing and exporting are no task for the driver.
    val canRename = state.mode == Mode.REVIEW && !state.driving && state.saved.any { it.file.path == state.coursePath }
    if (renameOpen && !canRename) renameOpen = false
    Box(Modifier.fillMaxSize().background(c.backgroundPrimary)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
            val landscape = maxWidth >= maxHeight
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PageNavigation(
                    state = state,
                    fullscreen = fullscreen,
                    canEnterFullscreen = canEnterFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    onRename = if (canRename) ({ renameOpen = true }) else null,
                    onShare = if (canRename) onShare else null,
                    onSettings = { settingsOpen = true },
                )
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.width(480.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Stats(state, columns = 2)
                            SavedCoursesCard(state, onOpen, onDelete, onToggleFavorite, { sortDialogOpen = true }, onSortDirection, onKeepRecent, Modifier.weight(1f).fillMaxWidth())
                            RecordButton(state, onStart, onStop, Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            MapCard(state, Modifier.weight(1.25f).fillMaxWidth())
                            DetailCard(state, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    PortraitContent(state, onStart, onStop, onOpen, onDelete, onToggleFavorite, { sortDialogOpen = true }, onSortDirection, onKeepRecent, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        val overlayModifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ButtonSize.Large.height + 24.dp)
        if (state.pendingDeletionCount > 0) {
            DeleteUndoOverlay(state.pendingDeletionCount, onUndoDelete, overlayModifier)
        } else {
            PleosToast(state.message, onDismissMessage, overlayModifier)
        }
        if (sortDialogOpen || settingsOpen || renameOpen) {
            // Draw inside the app window so the map and controls share the same backdrop.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
        }
    }
    if (sortDialogOpen) {
        CourseSortDialog(
            sort = state.courseSort,
            onDismiss = { sortDialogOpen = false },
            onSort = { sort -> sortDialogOpen = false; onCourseSort(sort) },
        )
    }
    if (renameOpen) {
        RenameDialog(
            name = state.title,
            onDismiss = { renameOpen = false },
            onRename = { name -> renameOpen = false; onRename(name) },
        )
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("설정", color = c.textPrimary) },
            text = {
                DisableDialogWindowDimming()
                Text("설정 항목이 없습니다", color = c.textSecondary)
            },
            confirmButton = { PleosButton("닫기", onClick = { settingsOpen = false }) },
            containerColor = c.surfaceBasic,
        )
    }
}

@Composable
private fun DisableDialogWindowDimming() {
    // The app draws the scrim; disable window dimming to avoid stacking two scrims.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
    }
}

@Composable
private fun DeleteUndoOverlay(count: Int, onUndo: () -> Unit, modifier: Modifier) {
    val c = PleosTheme.colors
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.widthIn(max = 792.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (c.isDark) Color(0xFF44464E) else Color(0xFF313236))
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                if (count == 1) "코스를 삭제했습니다" else "코스 ${count}개를 삭제했습니다",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            PleosButton(
                "삭제 취소", onClick = onUndo,
                container = Color.White.copy(alpha = 0.16f), contentColor = Color.White,
            )
        }
    }
}

@Composable
private fun PortraitContent(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (SavedCourse) -> Unit,
    onDelete: (SavedCourse) -> Unit,
    onToggleFavorite: (SavedCourse) -> Unit,
    onShowSort: () -> Unit,
    onSortDirection: () -> Unit,
    onKeepRecent: () -> Unit,
    modifier: Modifier,
) {
    val c = PleosTheme.colors
    val density = LocalDensity.current
    var statsHeightPx by remember { mutableIntStateOf(0) }
    val contentLayer = rememberGraphicsLayer()
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewportHeight = maxHeight
        val statsHeight = with(density) { statsHeightPx.toDp() }
        val mapHeight = ((viewportHeight - statsHeight - 48.dp) / 2).coerceAtLeast(160.dp)
        val sectionHeight = (viewportHeight - statsHeight - mapHeight - 48.dp).coerceAtLeast(160.dp)
        Column(
            Modifier.fillMaxSize().drawWithContent {
                contentLayer.record { this@drawWithContent.drawContent() }
                drawLayer(contentLayer)
            }.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(Modifier.onSizeChanged { statsHeightPx = it.height }) { Stats(state, columns = 4) }
            MapCard(state, Modifier.fillMaxWidth().height(mapHeight))
            DetailCard(state, Modifier.fillMaxWidth().height(sectionHeight))
            SavedCoursesCard(state, onOpen, onDelete, onToggleFavorite, onShowSort, onSortDirection, onKeepRecent, Modifier.fillMaxWidth().height(sectionHeight))
            // Let the last list row scroll clear of the fixed action.
            Spacer(Modifier.height(ButtonSize.Large.height + 24.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ButtonSize.Large.height + 24.dp)) {
            // Reuse the scrolling layer so the action's upper edge softly blurs the content below it.
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = 24.dp.toPx()),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .blur(20.dp)
                    .drawWithContent {
                        translate(top = size.height - contentLayer.size.height) { drawLayer(contentLayer) }
                    },
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to c.backgroundPrimary.copy(alpha = 0.94f),
                        1f to c.backgroundPrimary,
                    )
                )
            )
            RecordButton(state, onStart, onStop, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/** Page Navigation, Main type: app icon and title; the subtitle names the open course. */
@Composable
private fun PageNavigation(
    state: UiState,
    fullscreen: Boolean,
    canEnterFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onRename: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    val c = PleosTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The launcher icon is adaptive, which painterResource can't load: stack its layers and
        // show the middle 72 of 108, as the launcher mask does.
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(96.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(96.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (state.mode) {
                Mode.RECORDING -> "기록 중 · ${state.source?.title.orEmpty()}"
                Mode.REVIEW -> state.title
                Mode.IDLE -> state.source?.title ?: "Pleos 위치 서비스 없음"
            }
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(20.dp))
        if (onShare != null) {
            HeaderIconButton(icon = R.drawable.ic_share, description = "휴대폰으로 보내기", onClick = onShare)
            Spacer(Modifier.width(16.dp))
        }
        if (onRename != null) {
            HeaderIconButton(icon = R.drawable.ic_edit, description = "코스 이름 바꾸기", onClick = onRename)
            Spacer(Modifier.width(16.dp))
        }
        if (fullscreen || canEnterFullscreen) {
            HeaderIconButton(
                icon = if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                description = if (fullscreen) "전체 화면 종료" else "전체 화면",
                onClick = onToggleFullscreen,
            )
            Spacer(Modifier.width(16.dp))
        }
        HeaderIconButton(icon = R.drawable.ic_settings, description = "설정", onClick = onSettings)
    }
}

@Composable
private fun HeaderIconButton(icon: Int, description: String, onClick: () -> Unit) {
    val c = PleosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(ButtonSize.Medium.height)
            .pressScale(pressed)
            .clip(RoundedCornerShape(ButtonSize.Medium.radius))
            .background(if (pressed) c.buttonBasicPressed else c.buttonBasic)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = c.textPrimary,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun RecordButton(state: UiState, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    val c = PleosTheme.colors
    // While only the drive is recorded, the button marks the course; once marked, it saves it.
    val stopping = state.mode == Mode.RECORDING && state.marked
    PleosButton(
        label = when {
            stopping -> "기록 종료"
            else -> "기록 시작"
        },
        onClick = if (stopping) onStop else onStart,
        modifier = modifier,
        filled = true,
        size = ButtonSize.Large,
        // Parked, the screen is for reviewing courses only.
        enabled = !state.parked && (state.mode == Mode.RECORDING || state.source != null),
        // Stopping ends the session, so it wears the negative informative color.
        container = if (stopping) c.negative else null,
        contentColor = if (stopping) Color.White else null,
    )
}

/** Glanceable figures: one Headline value per cell, label above, unit beside. */
@Composable
private fun Stats(state: UiState, columns: Int) {
    val a = state.analysis
    val recording = state.mode == Mode.RECORDING
    val grade = a.samples.lastOrNull()?.let { a.gradeAt(it.distanceM) }
    val cells = buildList {
        val distanceLabel = if (a.distanceSource == DistanceSource.VEHICLE) "거리 (차량)" else "거리 (GPS)"
        val altitudeTag = if (a.altitudeSource == AltitudeSource.TERRAIN) "(지형)" else "(GPS)"
        add(StatCell(distanceLabel, "%.2f".format(a.totalDistanceM / 1000), "km"))
        add(StatCell("누적 상승", "%.0f".format(a.ascentM), "m"))
        if (recording) {
            add(StatCell("현재 고도 $altitudeTag", a.samples.lastOrNull()?.let { "%.0f".format(it.altitudeM) } ?: "–", "m"))
            add(StatCell("현재 경사", grade?.let { "%+.1f".format(it) } ?: "–", "%", grade?.let { Color(GradeBand.of(it).argb) }))
        } else {
            add(StatCell("누적 하강", "%.0f".format(a.descentM), "m"))
            add(StatCell("최고 고도 $altitudeTag", if (a.isEmpty) "–" else "%.0f".format(a.maxAltitudeM), "m"))
        }
    }
    val c = PleosTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceBasic).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { Stat(it, Modifier.weight(1f)) }
            }
        }
    }
}

private data class StatCell(val label: String, val value: String, val unit: String, val swatch: Color? = null)

@Composable
private fun Stat(cell: StatCell, modifier: Modifier) {
    val c = PleosTheme.colors
    Column(modifier) {
        Text(cell.label, style = MaterialTheme.typography.labelLarge, color = c.textTertiary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            cell.swatch?.let {
                Box(Modifier.size(20.dp).background(it, RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Text(cell.value, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, maxLines = 1)
            Text(" ${cell.unit}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun MapCard(state: UiState, modifier: Modifier) {
    val c = PleosTheme.colors
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        CourseMap(
            analysis = state.analysis,
            current = state.current,
            follow = state.mode == Mode.RECORDING,
            dark = c.isDark,
            modifier = Modifier.fillMaxSize(),
        )
        GradeLegend(
            stopCount = state.analysis.stops.size,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceBasic.copy(alpha = 0.92f))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun DetailCard(state: UiState, modifier: Modifier) {
    val c = PleosTheme.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(c.surfaceBasic).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PleosTabs(
            labels = listOf("고도 프로필", "km 스플릿"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )
        when (tab) {
            0 -> ElevationProfile(state.analysis, Modifier.fillMaxSize())
            1 -> if (state.analysis.splits.isEmpty()) {
                Empty("1 km를 달리면 스플릿이 생깁니다")
            } else {
                SplitsTable(state.analysis.splits, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun SavedCoursesCard(
    state: UiState,
    onOpen: (SavedCourse) -> Unit,
    onDelete: (SavedCourse) -> Unit,
    onToggleFavorite: (SavedCourse) -> Unit,
    onShowSort: () -> Unit,
    onSortDirection: () -> Unit,
    onKeepRecent: () -> Unit,
    modifier: Modifier,
) {
    val c = PleosTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(c.surfaceBasic).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("저장된 코스", Modifier.alignByBaseline(), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("${state.saved.size}", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            CourseSortControl(state.courseSort, state.sortDescending, onShowSort, onSortDirection)
        }
        state.recent?.takeIf { state.mode != Mode.RECORDING }?.let { RecentDrive(state, it, onOpen, onKeepRecent) }
        if (state.saved.isEmpty()) Empty("저장된 코스가 없습니다")
        else SavedCourses(state, onOpen, onDelete, onToggleFavorite)
    }
}

/** The whole last drive, for a road noticed too late to mark: open it, or keep it as a course. */
@Composable
private fun RecentDrive(state: UiState, recent: SavedCourse, onOpen: (SavedCourse) -> Unit, onKeep: () -> Unit) {
    val c = PleosTheme.colors
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth()
            .pressScale(pressed)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) c.buttonBasicPressed else c.buttonBasic)
            .clickable(interaction, indication = null, role = Role.Button) { onOpen(recent) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                RECENT_NAME, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1,
                fontWeight = if (state.coursePath == recent.file.path) FontWeight.Bold else FontWeight.Normal,
            )
            Text("${fmt.format(Date(recent.modifiedMillis))} 종료 · 다음 주행 전까지 보관", style = MaterialTheme.typography.labelLarge, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PleosButton("코스로 저장", onClick = onKeep, size = ButtonSize.Small)
    }
}

@Composable
private fun CourseSortControl(sort: CourseSort, descending: Boolean, onShowSort: () -> Unit, onDirection: () -> Unit) {
    val c = PleosTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.height(64.dp).clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = "정렬 기준 선택", onClick = onShowSort)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(sort.title, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        }
        Row(
            Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                .semantics { stateDescription = if (descending) "내림차순" else "오름차순" }
                .clickable(role = Role.Button, onClickLabel = if (descending) "오름차순으로 변경" else "내림차순으로 변경", onClick = onDirection),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_sort_up), contentDescription = "정렬 방향",
                tint = if (descending) c.textQuaternary else c.textPrimary, modifier = Modifier.size(width = 12.dp, height = 32.dp))
            Icon(painterResource(R.drawable.ic_sort_up), contentDescription = null,
                tint = if (descending) c.textPrimary else c.textQuaternary, modifier = Modifier.size(width = 12.dp, height = 32.dp).rotate(180f))
        }
    }
}

@Composable
private fun CourseSortDialog(sort: CourseSort, onSort: (CourseSort) -> Unit, onDismiss: () -> Unit) {
    val c = PleosTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(440.dp),
        title = { Text("정렬 기준", color = c.textPrimary) },
        text = {
            DisableDialogWindowDimming()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseSort.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (option == sort) c.surfaceHigh else Color.Transparent)
                            .selectable(selected = option == sort, role = Role.RadioButton) {
                                onSort(option)
                            }
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == sort,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = c.textPrimary, unselectedColor = c.textTertiary),
                        )
                        Text(option.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }
        },
        confirmButton = { PleosButton("닫기", onClick = onDismiss) },
        containerColor = c.surfaceBasic,
    )
}

@Composable
private fun RenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = PleosTheme.colors
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(name, selection = TextRange(0, name.length)))
    }
    val focus = remember { FocusRequester() }
    val valid = text.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(560.dp),
        title = { Text("코스 이름 바꾸기", color = c.textPrimary) },
        text = {
            DisableDialogWindowDimming()
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onRename(text.text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                    focusedBorderColor = c.textPrimary, unfocusedBorderColor = c.textTertiary, cursorColor = c.textPrimary,
                ),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { PleosButton("저장", onClick = { onRename(text.text) }, enabled = valid, filled = true) },
        dismissButton = { PleosButton("취소", onClick = onDismiss) },
        containerColor = c.surfaceBasic,
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PleosTheme.colors.textTertiary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedCourses(state: UiState, onOpen: (SavedCourse) -> Unit, onDelete: (SavedCourse) -> Unit, onToggleFavorite: (SavedCourse) -> Unit) {
    val c = PleosTheme.colors
    val recording = state.mode == Mode.RECORDING
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val density = LocalDensity.current
    val canDelete by rememberUpdatedState(!recording)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val courses = remember(state.saved, state.courseSort, state.sortDescending) {
        state.saved.sortedCourses(state.courseSort, state.sortDescending)
    }
    LaunchedEffect(state.courseSort, state.sortDescending, state.loadingDistances) {
        listState.scrollToItem(0)
    }
    // The list keeps its first visible row in place, so a course saved at the top would stay
    // hidden above it; follow the new first row instead.
    LaunchedEffect(courses.firstOrNull()?.file) {
        listState.scrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(courses, key = { it.file.path }) { course ->
            // Do not save a dismissed position: undo must restore a fully visible row.
            val swipe = remember(course.file, density) {
                SwipeToDismissBoxState(
                    initialValue = SwipeToDismissBoxValue.Settled,
                    density = density,
                    confirmValueChange = { it == SwipeToDismissBoxValue.Settled || canDelete },
                    positionalThreshold = { it * 0.4f },
                )
            }
            LaunchedEffect(swipe.currentValue, recording) {
                if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
                    if (recording) swipe.reset() else onDelete(course)
                }
            }
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            SwipeToDismissBox(
                state = swipe,
                modifier = Modifier.animateItem().clip(RoundedCornerShape(16.dp)),
                enableDismissFromStartToEnd = false,
                gesturesEnabled = !recording,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize()
                            .background(if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) c.surfaceBasic else c.negative)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text("삭제", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                },
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .pressScale(pressed)
                        .background(c.surfaceBasic)
                        .background(if (pressed) c.buttonBasic else Color.Transparent)
                        .clickable(interaction, indication = null, enabled = !recording) { onOpen(course) }
                        .semantics {
                            if (!recording) customActions = listOf(CustomAccessibilityAction("삭제") { onDelete(course); true })
                        },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                course.name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (course.name == state.title) FontWeight.Bold else FontWeight.Normal,
                            )
                            val detail = if (state.courseSort == CourseSort.DISTANCE) {
                                course.distanceM?.let { "%.2f km".format(it / 1000) }
                                    ?: if (state.loadingDistances) "거리 확인 중" else "거리 정보 없음"
                            } else fmt.format(Date(course.modifiedMillis))
                            Text(detail, style = MaterialTheme.typography.labelLarge, color = c.textTertiary)
                        }
                        FavoriteButton(
                            favorite = course.isFavorite,
                            enabled = !recording,
                            onClick = {
                                onToggleFavorite(course)
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.surfaceHigh)
                }
            }
        }
    }
}

@Composable
private fun FavoriteButton(favorite: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = PleosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(ButtonSize.Medium.height)
            .pressScale(pressed)
            .toggleable(
                value = favorite,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
            contentDescription = if (favorite) "즐겨찾기 해제" else "즐겨찾기 추가",
            tint = if (favorite) Color(0xFFFFC107) else c.textQuaternary,
            modifier = Modifier.size(36.dp),
        )
    }
}
