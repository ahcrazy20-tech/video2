package com.ahcrazy.video2.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Video2's dark palette with lighter text and distinct accents for status, links and actions. */
object V2 {
    val Bg = Color(0xFF0C111A)
    val Card = Color(0xFF171F2C)
    val CardSoft = Color(0xFF202B3B)
    val Text = Color(0xFFF1F5FB)
    val TextDim = Color(0xFFB6C4D7)
    val Accent = Color(0xFFFF827D)
    val Gold = Color(0xFFFFD17C)
    val Mint = Color(0xFF78E4BB)
    val Sky = Color(0xFFA5C8FF)
    val Outline = Color(0xFF52647C)
    val Divider = Color(0x27B6C4D7)
}

private val Scheme = darkColorScheme(
    primary = V2.Accent, onPrimary = Color(0xFF2A1114), primaryContainer = Color(0xFF472429), onPrimaryContainer = V2.Text,
    secondary = V2.Sky, onSecondary = V2.Bg, secondaryContainer = Color(0xFF253850), onSecondaryContainer = V2.Sky,
    tertiary = V2.Mint, onTertiary = V2.Bg,
    background = V2.Bg, surface = V2.Card, onBackground = V2.Text, onSurface = V2.Text,
    surfaceVariant = V2.CardSoft, onSurfaceVariant = V2.TextDim, outline = V2.Outline,
    error = V2.Accent, onError = V2.Bg
)

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
fun Video2Theme(rtl: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides (if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
        LocalContentColor provides V2.Text
    ) {
        MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
    }
}
