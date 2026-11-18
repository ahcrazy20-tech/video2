package com.ahcrazy.video2.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

private val Chrome = Color(0xF018202C)
private val ChromeSoft = Color(0xFF232E3D)
private val ChromeInk = Color(0xF0090D14)
private val ChromeLine = Color(0x3D9CAEC6)

/** The video fills the safe viewport. Chrome and captions share a measured column but don't resize the video. */
@Composable
internal fun PlayerFrame(
    chromeVisible: Boolean,
    insets: WindowInsets = WindowInsets.safeDrawing,
    video: @Composable BoxScope.() -> Unit,
    header: @Composable () -> Unit,
    center: @Composable BoxScope.() -> Unit,
    captions: @Composable () -> Unit,
    deck: @Composable (compact: Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player.root")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
            val compact = maxHeight < 330.dp
            Box(Modifier.matchParentSize().testTag("player.video"), content = video)
            Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (chromeVisible) header()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center, content = center)
                captions()
                if (chromeVisible) deck(compact)
            }
        }
    }
}

@Composable
internal fun PlayerHeader(ar: Boolean, title: String, kind: String?, status: String, onBack: () -> Unit, onTools: () -> Unit) {
    Surface(Modifier.widthIn(max = 860.dp).fillMaxWidth().testTag("player.header"), shape = RoundedCornerShape(20.dp),
        color = Chrome, border = BorderStroke(1.dp, ChromeLine), contentColor = V2.Text) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("player.back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (ar) "رجوع" else "Back", tint = V2.Text)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(V2.Mint, CircleShape))
                    Text(status, Modifier.weight(1f, fill = false), color = V2.TextDim, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    kind?.let {
                        Surface(shape = RoundedCornerShape(5.dp), color = ChromeSoft, contentColor = V2.Sky) {
                            Text(it, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), maxLines = 1,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            IconButton(onClick = onTools, modifier = Modifier.testTag("player.tools")) {
                Icon(Icons.Filled.MoreVert, if (ar) "أدوات المشغّل" else "Player tools", tint = V2.Text)
            }
        }
    }
}

@Composable
internal fun PlayerTransport(ar: Boolean, active: Boolean, ended: Boolean, seekable: Boolean, buffering: Boolean,
                             onBackTen: () -> Unit, onToggle: () -> Unit, onForwardTen: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = ChromeInk, border = BorderStroke(1.dp, ChromeLine)) {
                IconButton(onClick = onBackTen, enabled = seekable, modifier = Modifier.size(52.dp).testTag("player.rewind")) {
                    Icon(Icons.Filled.Replay10, if (ar) "ارجع ١٠ ثواني" else "Back 10 seconds", Modifier.size(25.dp),
                        tint = if (seekable) Color.White else V2.TextDim.copy(alpha = .6f))
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                if (buffering) CircularProgressIndicator(Modifier.matchParentSize(), color = V2.Mint, strokeWidth = 2.dp)
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp).testTag("player.play"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = V2.Mint, contentColor = V2.Bg)) {
                    Icon(when { ended -> Icons.Filled.Replay; active -> Icons.Filled.Pause; else -> Icons.Filled.PlayArrow },
                        when { ended -> if (ar) "إعادة التشغيل" else "Replay"; active -> if (ar) "إيقاف مؤقت" else "Pause"; else -> if (ar) "تشغيل" else "Play" },
                        Modifier.size(34.dp))
                }
            }
            Surface(shape = CircleShape, color = ChromeInk, border = BorderStroke(1.dp, ChromeLine)) {
                IconButton(onClick = onForwardTen, enabled = seekable, modifier = Modifier.size(52.dp).testTag("player.forward")) {
                    Icon(Icons.Filled.Forward10, if (ar) "قدّم ١٠ ثواني" else "Forward 10 seconds", Modifier.size(25.dp),
                        tint = if (seekable) Color.White else V2.TextDim.copy(alpha = .6f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerDeck(
    ar: Boolean, compact: Boolean, elapsed: Long, duration: Long, buffered: Float, seekable: Boolean, live: Boolean,
    rate: String, muted: Boolean, hasCaptions: Boolean, captionsOn: Boolean, landscape: Boolean,
    onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, onSpeed: () -> Unit, onMute: () -> Unit,
    onCaptions: () -> Unit, onRotate: () -> Unit,
) {
    val fraction = if (duration > 0) (elapsed.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    val elapsedText = MagicDuration.format(elapsed / 1000.0)
    val durationText = if (live) (if (ar) "مباشر" else "LIVE") else if (duration > 0) MagicDuration.format(duration / 1000.0) else "–:–"
    Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth().testTag("player.deck"), color = Chrome, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, ChromeLine)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 4.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(elapsedText, Modifier.testTag("player.elapsed"), style = timeStyle, color = V2.Text, maxLines = 1)
                    Slider(value = fraction, onValueChange = onScrub, onValueChangeFinished = onScrubEnd, enabled = seekable,
                        modifier = Modifier.weight(1f).testTag("player.seek").semantics {
                            contentDescription = if (ar) "موضع التشغيل" else "Playback position"
                            stateDescription = "$elapsedText / $durationText"
                        },
                        thumb = { Box(Modifier.size(12.dp).background(if (seekable) V2.Mint else V2.Outline, CircleShape)) },
                        track = {
                            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                val y = size.height / 2f
                                drawLine(V2.Outline, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                                if (seekable) {
                                    drawLine(V2.Sky.copy(alpha = .52f), Offset(0f, y), Offset(size.width * buffered.coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
                                    drawLine(V2.Mint, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                                }
                            }
                        })
                    Text(durationText, Modifier.testTag("player.duration"), style = timeStyle, color = if (live) V2.Mint else V2.TextDim, maxLines = 1)
                }
            }
            if (!compact) {
                HorizontalDivider(color = ChromeLine)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSpeed, modifier = Modifier.testTag("player.speed").semantics {
                        contentDescription = if (ar) "سرعة التشغيل" else "Playback speed"
                        stateDescription = rate
                    }) {
                        Text(rate, maxLines = 1, color = V2.Mint, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onMute, modifier = Modifier.testTag("player.mute")) {
                        Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            if (muted) (if (ar) "تشغيل الصوت" else "Unmute") else (if (ar) "كتم الصوت" else "Mute"),
                            tint = if (muted) V2.Mint else V2.Text)
                    }
                    IconButton(onClick = onCaptions, enabled = hasCaptions, modifier = Modifier.testTag("player.captions")) {
                        Icon(Icons.Filled.ClosedCaption, if (ar) "الترجمة" else "Captions",
                            tint = if (!hasCaptions) V2.TextDim.copy(alpha = .55f) else if (captionsOn) V2.Mint else V2.Text)
                    }
                    IconButton(onClick = onRotate, modifier = Modifier.testTag("player.rotate")) {
                        Icon(if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            if (landscape) (if (ar) "عرض رأسي" else "Portrait") else (if (ar) "عرض أفقي" else "Landscape"), tint = V2.Text)
                    }
                }
            }
        }
    }
}
