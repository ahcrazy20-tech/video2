package com.ahcrazy.video2.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Video2Appearance(val id: String, val ar: String, val en: String, val swatch: Color, val ink: Color) {
    GALAXY("galaxy", "جلاكسي", "Galaxy", Color(0xFF4B77F3), Color(0xFF0D1731)),
    NEBULA("nebula", "سديم", "Nebula", Color(0xFF9C60E7), Color(0xFF1D1335)),
    CLASSIC("classic", "كلاسيك", "Classic", Color(0xFFD95F65), Color(0xFF171F2C));

    companion object { fun fromStored(id: String?) = entries.firstOrNull { it.id == id } ?: GALAXY }
}

private data class Palette(
    val bg: Color, val top: Color, val card: Color, val soft: Color,
    val accent: Color, val button: Color, val outline: Color, val glass: Boolean = true
)
private val galaxy = Palette(Color(0xFF080E1E), Color(0xFF192D54), Color(0xFF14213B), Color(0xFF243658),
    Color(0xFFB2C9FF), Color(0xFF395DC6), Color(0xFF536991))
private val nebula = Palette(Color(0xFF110D20), Color(0xFF33204F), Color(0xFF211934), Color(0xFF35254F),
    Color(0xFFE1C5FF), Color(0xFF7042BA), Color(0xFF71598D))
private val classic = Palette(Color(0xFF0C111A), Color(0xFF0C111A), Color(0xFF171F2C), Color(0xFF202B3B),
    Color(0xFFFF827D), Color(0xFFA83643), Color(0xFF52647C), glass = false)
private val LocalPalette = staticCompositionLocalOf { galaxy }

object V2 {
    val Bg @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Card @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val CardSoft @Composable @ReadOnlyComposable get() = LocalPalette.current.soft
    val Accent @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val Action @Composable @ReadOnlyComposable get() = LocalPalette.current.button
    val Outline @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    val Text = Color(0xFFF3F6FF)
    val TextDim = Color(0xFFB9C8E2)
    val Danger = Color(0xFFFF9EAA)
    val Gold = Color(0xFFFFD48B)
    val Mint = Color(0xFF89EBCE)
    val Sky = Color(0xFFACCBFF)
    val Divider = Color(0x2EBACBEB)
}

/** Static color gradients approximate lit glass without animating or blurring the screen. */
@Composable
fun Modifier.galaxyBackground(): Modifier {
    val p = LocalPalette.current
    val brush = remember(p) { Brush.verticalGradient(0f to p.top, 0.56f to p.bg, 1f to p.bg) }
    return if (p.glass) background(brush) else background(p.bg)
}

@Composable
fun Modifier.glassSurface(shape: Shape, emphasized: Boolean = false): Modifier {
    val p = LocalPalette.current
    val fill = remember(p, emphasized) { Brush.verticalGradient(listOf(
        if (emphasized) p.top else p.soft.copy(alpha = .83f), p.card.copy(alpha = .96f))) }
    val edge = remember(p) { Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), p.outline.copy(alpha = .42f))) }
    return if (p.glass) clip(shape).background(fill).border(1.dp, edge, shape)
    else clip(shape).background(if (emphasized) p.soft else p.card)
}

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun Video2Theme(rtl: Boolean = true, appearance: Video2Appearance = Video2Appearance.GALAXY, content: @Composable () -> Unit) {
    val palette = when (appearance) {
        Video2Appearance.GALAXY -> galaxy
        Video2Appearance.NEBULA -> nebula
        Video2Appearance.CLASSIC -> classic
    }
    val scheme = remember(palette) { darkColorScheme(
        primary = palette.accent, onPrimary = palette.bg, primaryContainer = palette.soft, onPrimaryContainer = V2.Text,
        secondary = V2.Sky, onSecondary = palette.bg, secondaryContainer = palette.soft, onSecondaryContainer = V2.Sky,
        tertiary = V2.Mint, onTertiary = palette.bg,
        background = palette.bg, surface = palette.card, onBackground = V2.Text, onSurface = V2.Text,
        surfaceVariant = palette.soft, surfaceContainer = palette.card, surfaceContainerHigh = palette.soft,
        onSurfaceVariant = V2.TextDim, outline = palette.outline, error = V2.Danger, onError = palette.bg
    ) }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalLayoutDirection provides (if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
        LocalContentColor provides V2.Text
    ) { MaterialTheme(colorScheme = scheme, typography = Type, content = content) }
}
