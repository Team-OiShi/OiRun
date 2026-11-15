package io.github.team_oishi.oirun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Pleos Connect UI Design Guide (document.pleos.ai, docs-design), as Compose values.
 * Token names follow the guide's ivi_* tokens without the prefix.
 */

/** Grayscale scale: index = basic_00, 50, 100 … 900. Dark mirrors light. */
private object Gray {
    val c131417 = Color(0xFF131417)
    val c1D1E21 = Color(0xFF1D1E21)
    val c313236 = Color(0xFF313236)
    val c44464E = Color(0xFF44464E)
    val c686A72 = Color(0xFF686A72)
    val cC7C9CE = Color(0xFFC7C9CE)
    val cE0E1E6 = Color(0xFFE0E1E6)
    val cEDEEF2 = Color(0xFFEDEEF2)
    val cF7F8FA = Color(0xFFF7F8FA)
}

@Immutable
data class PleosColors(
    val isDark: Boolean,
    val backgroundPrimary: Color,
    val surfaceBasic: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    val buttonBasic: Color,
    val buttonBasicPressed: Color,
    val buttonFilled: Color,
    val buttonFilledPressed: Color,
    val buttonDisabled: Color,
    val onFilled: Color,
    val positive: Color,
    val negative: Color,
    val active: Color,
)

private val Light = PleosColors(
    isDark = false,
    backgroundPrimary = Gray.cF7F8FA,
    surfaceBasic = Color.White,
    surfaceLow = Gray.cF7F8FA,
    surfaceHigh = Gray.cEDEEF2,
    surfaceAccent = Gray.cE0E1E6,
    textPrimary = Gray.c131417,
    textSecondary = Gray.c131417.copy(alpha = 0.84f),
    textTertiary = Gray.c131417.copy(alpha = 0.64f),
    textQuaternary = Gray.c131417.copy(alpha = 0.32f),
    buttonBasic = Gray.c131417.copy(alpha = 0.05f),
    buttonBasicPressed = Gray.c131417.copy(alpha = 0.10f),
    buttonFilled = Gray.c44464E,
    buttonFilledPressed = Gray.c313236,
    buttonDisabled = Gray.c131417.copy(alpha = 0.05f),
    onFilled = Color.White,
    positive = Color(0xFF0064FF),
    negative = Color(0xFFFE3D16),
    active = Color(0xFF02C265),
)

private val Dark = PleosColors(
    isDark = true,
    // background_secondary: background_primary and surface_basic are both basic_50 in dark,
    // which would hide the cards' edges.
    backgroundPrimary = Gray.c131417,
    surfaceBasic = Gray.c1D1E21,
    surfaceLow = Gray.c131417,
    surfaceHigh = Gray.c313236,
    surfaceAccent = Gray.c44464E,
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.84f),
    textTertiary = Color.White.copy(alpha = 0.64f),
    textQuaternary = Color.White.copy(alpha = 0.32f),
    buttonBasic = Color.White.copy(alpha = 0.05f),
    buttonBasicPressed = Color.White.copy(alpha = 0.10f),
    buttonFilled = Gray.cE0E1E6,
    buttonFilledPressed = Gray.cC7C9CE,
    buttonDisabled = Color.White.copy(alpha = 0.05f),
    onFilled = Gray.c131417,
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4C28),
    active = Color(0xFF00E074),
)

/**
 * Font hierarchy. Sizes are the guide's px at the platform's 160 dpi, so 1 sp = 1 px; nothing
 * goes below the 18 px floor. The system font on Pleos is Asta Sans, so the default family is it.
 */
private val PleosTypography = Typography(
    headlineLarge = TextStyle(fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 56.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 30.sp, lineHeight = 40.sp),
    bodyMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp),
    bodySmall = TextStyle(fontSize = 26.sp, lineHeight = 36.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    labelMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    labelSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
)

private val LocalPleosColors = staticCompositionLocalOf { Light }

object PleosTheme {
    val colors: PleosColors
        @Composable get() = LocalPleosColors.current
}

/** Follows the system day/night mode, as the guide asks. */
@Composable
fun PleosTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (colors.isDark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalPleosColors provides colors) {
        MaterialTheme(
            colorScheme = scheme.copy(
                background = colors.backgroundPrimary,
                surface = colors.surfaceBasic,
                onSurface = colors.textPrimary,
            ),
            typography = PleosTypography,
            content = content,
        )
    }
}

enum class ButtonSize(val height: Dp, val radius: Dp) { Large(88.dp, 24.dp), Medium(64.dp, 20.dp), Small(48.dp, 16.dp) }

/**
 * Basic button: Primary is the light basic fill, Secondary the dark filled one. Every touchable
 * shrinks to 95 % while pressed and swaps to its pressed fill; there is no ripple.
 */
@Composable
fun PleosButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    container: Color? = null,
    contentColor: Color? = null,
) {
    val c = PleosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        !enabled -> c.buttonDisabled
        container != null -> container
        filled -> if (pressed) c.buttonFilledPressed else c.buttonFilled
        else -> if (pressed) c.buttonBasicPressed else c.buttonBasic
    }
    val ink = when {
        !enabled -> c.textQuaternary
        contentColor != null -> contentColor
        filled -> c.onFilled
        else -> c.textPrimary
    }
    Box(
        modifier
            .pressScale(pressed)
            .height(size.height)
            .clip(RoundedCornerShape(size.radius))
            .background(fill)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        val style = when (size) {
            ButtonSize.Large -> MaterialTheme.typography.titleSmall
            ButtonSize.Medium -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            ButtonSize.Small -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        }
        Text(label, style = style, color = ink, maxLines = 1)
    }
}

fun Modifier.pressScale(pressed: Boolean): Modifier =
    graphicsLayer { val s = if (pressed) 0.95f else 1f; scaleX = s; scaleY = s }

/** Tabs, Box type: a surface_high track with the selected item lifted onto surface_basic. */
@Composable
fun PleosTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PleosTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceHigh)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .pressScale(pressed)
                    .height(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) c.surfaceBasic else Color.Transparent)
                    .clickable(interaction, indication = null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                    color = if (isSelected) c.textPrimary else c.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Toast: 24 dp from the window's sides and bottom, at most 792 dp wide; clears itself. */
@Composable
fun PleosToast(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    message ?: return
    LaunchedEffect(message) {
        delay(4_000)
        onDismiss()
    }
    val c = PleosTheme.colors
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .widthIn(max = 792.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (c.isDark) Color(0xFF44464E) else Color(0xFF313236))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(horizontal = 40.dp, vertical = 20.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.widthIn(max = 712.dp),
            )
        }
    }
}
