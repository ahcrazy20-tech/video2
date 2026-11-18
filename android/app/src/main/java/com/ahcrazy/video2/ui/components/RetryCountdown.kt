package com.ahcrazy.video2.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun cooldownSeconds(untilMs: Long?): Long {
    fun remaining() = ((untilMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
    fun seconds(ms: Long) = ms / 1_000L + if (ms % 1_000L == 0L) 0L else 1L
    var left by remember(untilMs) { mutableLongStateOf(seconds(remaining())) }
    LaunchedEffect(untilMs) {
        while (true) {
            val ms = remaining()
            left = seconds(ms)
            if (ms == 0L) break
            delay(minOf(1_000L, ms))
        }
    }
    return left
}

internal fun formatCooldown(seconds: Long, ar: Boolean): String = when {
    seconds >= 172_800 -> if (ar) "أكتر من يوم" else "over a day"
    seconds >= 3600 -> String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else -> String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
