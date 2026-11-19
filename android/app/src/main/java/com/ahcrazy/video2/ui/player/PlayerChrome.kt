package com.ahcrazy.video2.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.ui.theme.V2

private val CinemaShade = Color(0xE8060A14)

/** Full-bleed video; the header, caption slot and deck keep their own safe layout. */
@Composable
internal fun PlayerFrame(
    chromeVisible: Boolean,
    insets: WindowInsets = WindowInsets.safeDrawing,
    video: @Composable BoxScope.() -> Unit,
    header: @Composable () -> Unit,
    center: @Composable BoxScope.() -> Unit,
    captions: @Composable () -> Unit,
    deck: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player.root")) {
        Box(Modifier.matchParentSize().testTag("player.video"), content = video)
        if (chromeVisible) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(105.dp)
                .background(Brush.verticalGradient(listOf(CinemaShade, Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(165.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color(0xA6060A14), 1f to CinemaShade)))
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (chromeVisible) header()
            // Reserve the chrome before measuring captions, including on a short landscape phone.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = center)
                Box(Modifier.align(Alignment.BottomCenter)) { captions() }
            }
            if (chromeVisible) deck()
        }
    }
}

@Composable
internal fun PlayerHeader(ar: Boolean, title: String, kind: String?, status: String, onBack: () -> Unit, onTools: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("player.header"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("player.back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, if (ar) "رجوع" else "Back", tint = Color.White)
        }
        Text(title, Modifier.weight(1f).padding(horizontal = 4.dp).semantics {
            stateDescription = listOfNotNull(status, kind).joinToString(" · ")
        }, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onTools, modifier = Modifier.size(48.dp).testTag("player.tools")) {
            Icon(Icons.Outlined.Tune, if (ar) "أدوات المشغّل" else "Player tools", tint = Color.White)
        }
    }
}

@Composable
internal fun PlayerTransport(ar: Boolean, active: Boolean, ended: Boolean, seekable: Boolean, buffering: Boolean,
                             onBackTen: () -> Unit, onToggle: () -> Unit, onForwardTen: () -> Unit, enabled: Boolean = true) {
    // Like the timeline, forward is always on the physical right, even in RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackTen, enabled = enabled && seekable, modifier = Modifier.size(48.dp).testTag("player.rewind")) {
                Icon(Icons.Filled.Replay10, if (ar) "ارجع ١٠ ثواني" else "Back 10 seconds", Modifier.size(24.dp),
                    tint = if (seekable && enabled) Color.White else V2.TextDim.copy(alpha = .5f))
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (buffering) CircularProgressIndicator(Modifier.size(42.dp), color = V2.Accent, strokeWidth = 2.dp)
                IconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.matchParentSize().testTag("player.play")) {
                    Box(Modifier.size(38.dp).background(Color(0x6190ACE7), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(when { ended -> Icons.Filled.Replay; active -> Icons.Filled.Pause; else -> Icons.Filled.PlayArrow },
                            when { ended -> if (ar) "إعادة التشغيل" else "Replay"; active -> if (ar) "إيقاف مؤقت" else "Pause"; else -> if (ar) "تشغيل" else "Play" },
                            Modifier.size(25.dp), tint = if (enabled) Color.White else V2.TextDim)
                    }
                }
            }
            IconButton(onClick = onForwardTen, enabled = enabled && seekable, modifier = Modifier.size(48.dp).testTag("player.forward")) {
                Icon(Icons.Filled.Forward10, if (ar) "قدّم ١٠ ثواني" else "Forward 10 seconds", Modifier.size(24.dp),
                    tint = if (seekable && enabled) Color.White else V2.TextDim.copy(alpha = .5f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerDeck(
    ar: Boolean, elapsed: Long, duration: Long, buffered: Float, seekable: Boolean, live: Boolean,
    hasCaptions: Boolean, captionsOn: Boolean, landscape: Boolean,
    onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, onCaptions: () -> Unit, onRotate: () -> Unit,
    transport: @Composable () -> Unit,
) {
    val fraction = if (duration > 0) (elapsed.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val accent = V2.Accent
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    val elapsedText = MagicDuration.format(elapsed / 1000.0)
    val durationText = if (live) (if (ar) "مباشر" else "LIVE") else if (duration > 0) MagicDuration.format(duration / 1000.0) else "–:–"
    Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().testTag("player.deck")) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(elapsedText, Modifier.testTag("player.elapsed"), style = timeStyle, color = Color.White, maxLines = 1)
                Box(Modifier.weight(1f).testTag("player.timeline")) {
                    Slider(value = fraction, onValueChange = onScrub, onValueChangeFinished = onScrubEnd, enabled = seekable,
                        modifier = Modifier.fillMaxWidth().testTag("player.seek").semantics {
                            contentDescription = if (ar) "موضع التشغيل" else "Playback position"
                            stateDescription = "$elapsedText / $durationText"
                        },
                        thumb = { Box(Modifier.size(12.dp).background(if (seekable) accent else V2.TextDim, CircleShape)) },
                        track = {
                            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                                val y = size.height / 2f
                                drawLine(Color.White.copy(alpha = .25f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                                if (seekable) {
                                    drawLine(accent.copy(alpha = .48f), Offset(0f, y), Offset(size.width * buffered.coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
                                    drawLine(accent, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                                }
                            }
                        })
                }
                Text(durationText, Modifier.testTag("player.duration"), style = timeStyle, color = if (live) accent else V2.TextDim, maxLines = 1)
            }
            val captionButton: @Composable () -> Unit = {
                IconButton(onClick = onCaptions, enabled = hasCaptions, modifier = Modifier.size(48.dp).testTag("player.captions")) {
                    Icon(Icons.Outlined.ClosedCaption, if (ar) "الترجمة" else "Captions", tint = when {
                        !hasCaptions -> V2.TextDim.copy(alpha = .45f)
                        captionsOn -> accent
                        else -> Color.White
                    })
                }
            }
            val rotateButton: @Composable () -> Unit = {
                IconButton(onClick = onRotate, modifier = Modifier.size(48.dp).testTag("player.rotate")) {
                    Icon(if (landscape) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        if (landscape) (if (ar) "عرض رأسي" else "Portrait") else (if (ar) "عرض أفقي" else "Landscape"), tint = Color.White)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 380.dp) {
                    Box(Modifier.align(Alignment.Center)) { transport() }
                    Row(Modifier.align(Alignment.CenterEnd)) { captionButton(); rotateButton() }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        captionButton(); transport(); rotateButton()
                    }
                }
            }
        }
    }
}
