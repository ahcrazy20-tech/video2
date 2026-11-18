package com.ahcrazy.video2.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SubMode(val ar: String, val en: String) {
    OFF("إيقاف", "Off"), ORIGINAL("الأصلي", "Original"), TRANSLATED("المترجم", "Translated"), BILINGUAL("ثنائي", "Bilingual")
}

/**
 * Offline + streaming player — port of OfflinePlayerView/MagicPlayerView on Media3 ExoPlayer.
 * ExoPlayer advantage over iOS AVPlayer: MP4/HLS/DASH/WebM/MKV all play natively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    video: SavedVideo?, streamUrl: String?, streamTitle: String?, ar: Boolean,
    streamKind: MediaKind? = null, streamAuth: DownloadAuth? = null, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val app = activity.app()
    val scope = rememberCoroutineScope()
    val uri = video?.localPath?.let { android.net.Uri.fromFile(File(it)) } ?: streamUrl?.let { android.net.Uri.parse(it) }
    val title = video?.title ?: streamTitle ?: "فيديو"

    val player = remember(uri, streamAuth, streamKind) {
        val builder = ExoPlayer.Builder(ctx)
        if (streamUrl != null && streamAuth != null) {
            val network = OkHttpDataSource.Factory(app.downloads.http.playerClient(streamAuth))
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, network)))
        }
        builder.build().apply {
            if (uri != null) {
                val mime = when (streamKind ?: video?.kind) {
                    MediaKind.HLS -> MimeTypes.APPLICATION_M3U8
                    MediaKind.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
                setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build())
                if (video != null && video.lastPositionSec > 5) seekTo((video.lastPositionSec * 1000).toLong())
                prepare(); playWhenReady = true
            }
        }
    }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var rate by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var subMode by remember { mutableStateOf(SubMode.BILINGUAL) }
    var subSize by remember { mutableStateOf(18) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val cues = remember(video?.id, video?.subtitleFiles) {
        val orig = video?.subtitleFiles?.get("orig")?.let { SrtCodec.parse(File(it)) } ?: emptyList()
        val tran = video?.subtitleFiles?.get("target")?.let { SrtCodec.parse(File(it)) } ?: emptyList()
        orig.mapIndexed { i, c -> c.copy(translated = tran.getOrNull(i)?.original ?: c.translated) }
            .ifEmpty { tran }
    }
    // Dubbed audio side-player (synced, original muted)
    val dubPlayer = remember(video?.dubbedAudioPath) {
        video?.dubbedAudioPath?.let { path ->
            ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path)))); prepare() }
        }
    }
    var dubOn by remember { mutableStateOf(video?.hasDubbedAudio == true) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition; duration = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            // Drift-correct dubbed audio
            if (dubOn && dubPlayer != null) {
                val drift = kotlin.math.abs(dubPlayer.currentPosition - player.currentPosition)
                if (drift > 350) dubPlayer.seekTo(player.currentPosition)
                if (player.isPlaying && !dubPlayer.isPlaying) dubPlayer.play()
                if (!player.isPlaying && dubPlayer.isPlaying) dubPlayer.pause()
                player.volume = 0f
            } else player.volume = if (muted) 0f else 1f
            delay(300)
        }
    }
    DisposableEffect(player) {
        onDispose {
            if (video != null) app.library.updatePosition(video, position / 1000.0, duration / 1000.0)
            player.release(); dubPlayer?.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val nowSec = position / 1000.0
    val currentOrig = SrtCodec.cueAt(cues, nowSec, translated = false)
    val currentTran = SrtCodec.cueAt(cues, nowSec, translated = true)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxSize())

        // Subtitle overlay
        if (subMode != SubMode.OFF && cues.isNotEmpty()) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 118.dp, start = 22.dp, end = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (subMode == SubMode.ORIGINAL || subMode == SubMode.BILINGUAL) currentOrig?.let {
                    SubtitleLine(it, subSize, Color.White)
                }
                if (subMode == SubMode.TRANSLATED || subMode == SubMode.BILINGUAL) currentTran?.let {
                    SubtitleLine(it, subSize, V2.Gold)
                }
            }
        }

        if (locked) {
            IconButton(onClick = { locked = false }, Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
        } else {
            // Top bar
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(video?.kind?.titleAr ?: "بث مباشر", color = V2.TextDim, fontSize = 11.sp)
                }
                IconButton(onClick = { activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }) {
                    Icon(Icons.Filled.PictureInPictureAlt, null, tint = Color.White)
                }
                IconButton(onClick = { locked = true }) { Icon(Icons.Filled.Lock, null, tint = Color.White) }
                Box {
                    IconButton(onClick = { showMore = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text(if (ar) "لقطة (Snapshot)" else "Snapshot") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) }, onClick = {
                                showMore = false
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { saveSnapshot(ctx, video, nowSec) }
                                    toast = if (ok) (if (ar) "اتحفظت في الصور ✓" else "Saved to Photos ✓") else (if (ar) "فشلت اللقطة" else "Snapshot failed")
                                }
                            })
                        DropdownMenuItem(text = { Text(if (ar) "مشاركة الفيديو" else "Share video") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { showMore = false })
                        DropdownMenuItem(text = { Text(if (ar) "أفقي / رأسي" else "Landscape / Portrait") },
                            leadingIcon = { Icon(Icons.Filled.ScreenRotation, null) }, onClick = {
                                showMore = false
                                activity.requestedOrientation = if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            })
                    }
                }
            }

            // Center controls
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.seekTo((position - 10_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, Modifier.size(72.dp)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(62.dp))
                }
                IconButton(onClick = { player.seekTo(position + 10_000) }) {
                    Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }

            // Bottom controls
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MagicDuration.format(nowSec), color = Color.White, fontSize = 11.sp)
                    Slider(value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = V2.Accent, activeTrackColor = V2.Accent))
                    Text("-" + MagicDuration.format(((duration - position).coerceAtLeast(0)) / 1000.0), color = V2.TextDim, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSpeed = true }) { Text(if (rate == 1f) "السرعة" else "×$rate", color = Color.White, fontSize = 12.sp) }
                    IconButton(onClick = { muted = !muted }) { Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, null, tint = Color.White) }
                    IconButton(onClick = { showSubs = true }) { Icon(Icons.Filled.ClosedCaption, null, tint = if (subMode == SubMode.OFF) Color.White else V2.Gold) }
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, null, tint = Color.White) }
                    if (video?.hasDubbedAudio == true) TextButton(onClick = { dubOn = !dubOn }) {
                        Text("🎙 " + if (dubOn) "الدبلجة شغالة" else "الدبلجة", color = if (dubOn) V2.Gold else Color.White, fontSize = 11.sp)
                    }
                    IconButton(onClick = { loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }) {
                        Icon(Icons.Filled.Repeat, null, tint = if (loop) V2.Gold else Color.White)
                    }
                    IconButton(onClick = { showSleep = true }) { Icon(Icons.Filled.Timer, null, tint = Color.White) }
                }
            }
        }
        toast?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 76.dp).clip(RoundedCornerShape(50)).background(V2.Card).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(it, color = Color.White, fontSize = 12.sp)
            }
            LaunchedEffect(it) { delay(2200); toast = null }
        }
    }

    if (showSpeed) AlertDialog(onDismissRequest = { showSpeed = false },
        title = { Text(if (ar) "سرعة التشغيل" else "Playback speed") },
        text = { Column { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
            TextButton(onClick = { rate = r; player.setPlaybackSpeed(r); showSpeed = false }) {
                Text(if (r == 1f) (if (ar) "عادي ×1" else "Normal ×1") else "×$r", color = if (r == rate) V2.Accent else Color.White)
            } } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { showSpeed = false }) { Text("✕") } })

    if (showSleep) AlertDialog(onDismissRequest = { showSleep = false },
        title = { Text(if (ar) "مؤقت النوم" else "Sleep timer") },
        text = { Column { listOf(0, 15, 30, 45, 60).forEach { m ->
            TextButton(onClick = {
                showSleep = false
                if (m > 0) scope.launch { delay(m * 60_000L); player.pause(); toast = "⏰ " + (if (ar) "وقف التشغيل" else "Paused by sleep timer") }
                else toast = if (ar) "المؤقت مقفول" else "Timer off"
            }) { Text(if (m == 0) (if (ar) "إيقاف المؤقت" else "Off") else "$m min") } } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { showSleep = false }) { Text("✕") } })

    if (showSubs) ModalBottomSheet(onDismissRequest = { showSubs = false }) {
        Column(Modifier.padding(20.dp)) {
            Text(if (ar) "الترجمة على الشاشة" else "On-screen subtitles", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SubMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { subMode = m }.padding(vertical = 11.dp)) {
                    Text(if (ar) m.ar else m.en, modifier = Modifier.weight(1f), color = if (m == subMode) V2.Accent else Color.White)
                    if (m == subMode) Text("✓", color = V2.Accent)
                }
            }
            Text(if (ar) "حجم الخط" else "Font size", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf(14, 18, 23, 28).forEach { s ->
                    TextButton(onClick = { subSize = s }) { Text("$s", color = if (s == subSize) V2.Accent else Color.White) }
                }
            }
            if (cues.isEmpty()) Text(if (ar) "الفيديو ده لسه ملوش ترجمة — اعملها من تبويب الترجمة." else "No subtitles yet — create them from the Translate tab.", fontSize = 12.sp, color = V2.TextDim)
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showSearch) ModalBottomSheet(onDismissRequest = { showSearch = false }) {
        var q by remember { mutableStateOf("") }
        val hits = cues.filter { q.isBlank() || it.original.contains(q, true) || it.translated.contains(q, true) }
        Column(Modifier.padding(20.dp)) {
            Text(if (ar) "بحث في الترجمة" else "Search subtitles", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(q, { q = it }, placeholder = { Text(if (ar) "اكتب كلمة…" else "Type a word…") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            LazyColumn {
                items(hits.take(60)) { c ->
                    Column(Modifier.fillMaxWidth().clickable { player.seekTo((c.startSec * 1000).toLong()); showSearch = false }.padding(vertical = 8.dp)) {
                        Text(MagicDuration.format(c.startSec), color = V2.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(c.original, fontSize = 13.sp)
                        if (c.translated.isNotBlank()) Text(c.translated, fontSize = 12.sp, color = V2.TextDim)
                    }
                }
            }
        }
    }
}

@Composable private fun SubtitleLine(text: String, sizeSp: Int, color: Color) {
    Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.68f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = sizeSp.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
    }
}

private fun saveSnapshot(ctx: android.content.Context, video: SavedVideo?, atSec: Double): Boolean {
    if (video == null) return false
    return runCatching {
        val r = MediaMetadataRetriever(); r.setDataSource(video.localPath)
        val bmp = r.getFrameAtTime((atSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "video2-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Video2")
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        r.release(); true
    }.getOrDefault(false)
}
