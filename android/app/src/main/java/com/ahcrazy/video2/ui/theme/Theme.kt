package com.ahcrazy.video2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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

/** Exact palette of the iOS V2Theme. */
object V2 {
    val Bg = Color(0xFF0F1219)
    val Card = Color(0xFF1C212E)
    val CardSoft = Color(0xFF232939)
    val Accent = Color(0xFFF25C57)
    val Gold = Color(0xFFF2C259)
    val Mint = Color(0xFF66D9B8)
    val TextDim = Color(0xFF9AA3B5)
    val Divider = Color(0x17FFFFFF)
}

private val Scheme = darkColorScheme(
    primary = V2.Accent, secondary = V2.Gold, tertiary = V2.Mint,
    background = V2.Bg, surface = V2.Card, onBackground = Color.White,
    onSurface = Color.White, error = V2.Accent
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp, color = V2.TextDim),
    labelSmall = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun Video2Theme(rtl: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
    }
}
