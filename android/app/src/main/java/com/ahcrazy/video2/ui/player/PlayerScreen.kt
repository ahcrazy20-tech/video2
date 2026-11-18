package com.ahcrazy.video2.ui.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private enum class Panel { TOOLS, SPEED, CAPTIONS, SEARCH, SLEEP }

private data class Playback(
    val playing: Boolean, val active: Boolean, val buffering: Boolean, val ended: Boolean, val seekable: Boolean,
    val live: Boolean, val duration: Long, val error: Int?, val height: Int,
)

private fun Player.playback() = Playback(isPlaying, playWhenReady && playbackState != Player.STATE_ENDED && playerError == null,
    playbackState == Player.STATE_BUFFERING, playbackState == Player.STATE_ENDED, isCurrentMediaItemSeekable,
    isCurrentMediaItemLive, duration.coerceAtLeast(0L), playerError?.errorCode, videoSize.height)

private fun rateLabel(rate: Float) = "${rate.toString().removeSuffix(".0")}×"

@Composable
fun PlayerScreen(
    video: SavedVideo?, streamUrl: String?, streamTitle: String?, ar: Boolean,
    streamKind: MediaKind? = null, streamAuth: DownloadAuth? = null, onBack: () -> Unit,
) {
    val activity = LocalContext.current as ComponentActivity
    val ctx = activity.applicationContext
    val app = activity.app()
    val scope = rememberCoroutineScope()
    val uri = remember(video?.localPath, streamUrl) { video?.let { Uri.fromFile(File(it.localPath)) } ?: streamUrl?.let(Uri::parse) }
    val kind = video?.kind ?: streamKind
    val title = video?.title ?: streamTitle ?: if (ar) "فيديو" else "Video"
    val player = remember(uri, streamAuth, kind) {
        ExoPlayer.Builder(ctx).apply {
            if (video == null && streamAuth != null) {
                val network = OkHttpDataSource.Factory(app.downloads.http.playerClient(streamAuth))
                setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, network)))
            }
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }.build().apply {
            if (uri != null) {
                val mime = when (kind) {
                    MediaKind.HLS -> MimeTypes.APPLICATION_M3U8
                    MediaKind.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
                setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build())
                if (video != null && video.lastPositionSec > 5) seekTo((video.lastPositionSec * 1_000).toLong())
                prepare()
                playWhenReady = true
            }
        }
    }
    var playback by remember(player) { mutableStateOf(player.playback()) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var buffered by remember(player) { mutableLongStateOf(player.bufferedPosition) }
    var rate by remember(player) { mutableFloatStateOf(1f) }
    var muted by remember(player) { mutableStateOf(false) }
    var loop by remember(player) { mutableStateOf(false) }
    var zoomed by remember(player) { mutableStateOf(false) }
    var locked by remember(player) { mutableStateOf(false) }
    var visible by remember(player) { mutableStateOf(true) }
    var activityTick by remember(player) { mutableIntStateOf(0) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    var panel by remember(player) { mutableStateOf<Panel?>(null) }
    var deadline by remember(player) { mutableLongStateOf(0L) }
    var notice by remember(player) { mutableStateOf<String?>(null) }
    var pip by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val canPip = remember(ctx) { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    val touchExploration = ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    val cues by produceState(emptyList<SubCue>(), video?.subtitleFiles) {
        value = withContext(Dispatchers.IO) {
            fun read(key: String) = video?.subtitleFiles?.get(key)?.let { runCatching { SrtCodec.parse(File(it)) }.getOrNull() }.orEmpty()
            val original = read("orig")
            val translation = read("target")
            if (original.isEmpty()) translation.map { it.copy(original = "", translated = it.original) }
            else original.mapIndexed { i, cue -> cue.copy(translated = translation.getOrNull(i)?.original ?: cue.translated) }
        }
    }
    val captionModes = remember(cues) {
        val original = cues.any { it.original.isNotBlank() }
        val translated = cues.any { it.translated.isNotBlank() }
        listOfNotNull(SubMode.OFF, SubMode.ORIGINAL.takeIf { original }, SubMode.TRANSLATED.takeIf { translated }, SubMode.BILINGUAL.takeIf { original && translated })
    }
    var chosenMode by remember(player) { mutableStateOf<SubMode?>(null) }
    val subMode = chosenMode?.takeIf { it in captionModes } ?: captionModes.last()
    var subSize by remember(player) { mutableIntStateOf(18) }
    val dubPath = remember(video?.dubbedAudioPath) { video?.dubbedAudioPath?.takeIf { File(it).isFile } }
    val dub = remember(dubPath) {
        dubPath?.let { path -> ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); prepare() } }
    }
    var dubOn by remember(player, dub) { mutableStateOf(dub != null) }
    val canDub = dub != null

    DisposableEffect(activity) {
        val previousOrientation = activity.requestedOrientation
        val callback = Consumer<PictureInPictureModeChangedInfo> { info -> pip = info.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(callback)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(callback)
            if (activity.requestedOrientation != previousOrientation) activity.requestedOrientation = previousOrientation
        }
    }
    DisposableEffect(player, activity) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playback = p.playback()
                position = p.currentPosition
                buffered = p.bufferedPosition
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) player.pause()
        }
        player.addListener(listener)
        activity.lifecycle.addObserver(lifecycle)
        onDispose {
            activity.lifecycle.removeObserver(lifecycle)
            player.removeListener(listener)
            if (video != null) {
                val last = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
                app.library.updatePosition(video, last / 1_000.0, player.duration.takeIf { it > 0 }?.div(1_000.0))
            }
            player.release()
        }
    }
    DisposableEffect(dub) { onDispose { dub?.release() } }
    LaunchedEffect(player, playback.playing, playback.buffering) {
        if (!playback.playing && !playback.buffering) return@LaunchedEffect
        while (true) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            delay(250)
        }
    }
    val dubActive = dubOn && dub != null && dub.playerError == null
    LaunchedEffect(player, dub, dubActive, muted, playback.playing, rate) {
        player.volume = if (muted || dubActive) 0f else 1f
        if (dub == null) return@LaunchedEffect
        dub.volume = if (muted) 0f else 1f
        dub.setPlaybackSpeed(rate)
        if (!dubActive) { dub.pause(); return@LaunchedEffect }
        dub.seekTo(player.currentPosition)
        dub.playWhenReady = playback.playing
        while (playback.playing) {
            if (dub.playerError != null) { dubOn = false; break }
            if (abs(dub.currentPosition - player.currentPosition) > 350) dub.seekTo(player.currentPosition)
            delay(350)
        }
    }
    LaunchedEffect(player, deadline) {
        if (deadline != 0L) {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            player.pause()
            deadline = 0L
            notice = if (ar) "المؤقت وقف التشغيل" else "Paused by sleep timer"
        }
    }
    LaunchedEffect(visible, playback.playing, playback.buffering, locked, panel, scrub != null, activityTick, pip, touchExploration) {
        if (visible && playback.playing && !playback.buffering && !locked && panel == null && scrub == null && !pip && !touchExploration) {
            delay(4_500)
            visible = false
        }
    }
    LaunchedEffect(playback.error, playback.ended, pip) {
        if (!pip && (playback.error != null || playback.ended)) visible = true
    }
    LaunchedEffect(notice) {
        if (notice != null) { delay(2_400); notice = null }
    }

    fun seek(to: Long) {
        if (!playback.seekable) return
        val target = to.coerceIn(0L, playback.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
        position = target
        if (dubActive) dub?.seekTo(target)
        activityTick++
    }
    fun jump(offset: Long) = seek(player.currentPosition + offset)
    fun toggle() {
        if (playback.active) player.pause()
        else { if (playback.ended) player.seekToDefaultPosition(); player.play() }
        activityTick++
    }
    fun rotate() {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activityTick++
    }
    fun onMute() { muted = !muted; activityTick++ }
    fun enterPip() {
        if (!canPip) return
        val pixels = player.videoSize
        val aspect = Rational(pixels.width, pixels.height).takeIf { it.toDouble() in (1 / 2.39)..2.39 } ?: Rational(16, 9)
        if (!runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(aspect).build()) }.getOrDefault(false)) {
            notice = if (ar) "صورة داخل صورة مش متاحة دلوقتي" else "Picture-in-picture is unavailable right now"
        }
    }
    fun snapshot(time: Long, document: Uri? = null) {
        video ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { savePlayerSnapshot(ctx, video, time, document) }
            notice = if (ok) (if (ar) "اللقطة اتحفظت" else "Snapshot saved") else (if (ar) "ما قدرناش نحفظ اللقطة" else "Couldn’t save the snapshot")
        }
    }
    var snapshotAt by remember(player) { mutableLongStateOf(0L) }
    val createSnapshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { result ->
        result?.let { snapshot(snapshotAt, it) }
    }

    BackHandler(!pip && locked) { locked = false; visible = true }
    val chrome = !pip && !locked && visible
    val toggleChrome by rememberUpdatedState<() -> Unit>({ if (!locked && !pip) { visible = !visible; activityTick++ } })
    val gestureJump by rememberUpdatedState<(Long) -> Unit>({ offset ->
        if (!locked && !pip && playback.seekable && playback.error == null) { jump(offset); visible = true }
    })
    val clock = scrub?.let { (it * playback.duration).toLong() } ?: position
    val at = position / 1_000.0
    val mode = video?.let { if (ar) "ملف محفوظ" else "Saved file" }
        ?: if (playback.live) (if (ar) "بث مباشر" else "Live stream") else (if (ar) "تشغيل عبر الإنترنت" else "Streaming")
    val format = listOfNotNull(kind?.ext?.uppercase()?.takeIf { kind != MediaKind.OTHER }, playback.height.takeIf { it > 0 }?.let { "${it}p" })
        .joinToString(" · ").ifBlank { null }

    PlayerFrame(chromeVisible = chrome, video = {
        AndroidView(factory = { PlayerView(it).apply { useController = false; isClickable = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            modifier = Modifier.fillMaxSize(), onReset = null, onRelease = { it.player = null }, update = { view ->
                if (view.player != player) view.player = player
                view.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.keepScreenOn = playback.playing && !pip
            })
        if (!pip && !locked) Box(Modifier.matchParentSize().pointerInput(playback.seekable, playback.error) {
            if (playback.seekable && playback.error == null) detectTapGestures(onTap = { toggleChrome() }, onDoubleTap = { offset ->
                gestureJump(if (offset.x < size.width / 2f) -10_000L else 10_000L)
            }) else detectTapGestures(onTap = { toggleChrome() })
        }.semantics {
            onClick(label = if (visible) (if (ar) "اخفِ أدوات التشغيل" else "Hide controls") else (if (ar) "اعرض أدوات التشغيل" else "Show controls")) {
                toggleChrome(); true
            }
        })
    }, header = {
        PlayerHeader(ar, title, format, mode, onBack, onTools = { panel = Panel.TOOLS })
    }, center = {
        when {
            pip -> Unit
            locked -> FilledTonalIconButton(onClick = { locked = false; visible = true; activityTick++ }, Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.Lock, if (ar) "افتح أدوات التشغيل" else "Unlock controls", tint = V2.Mint)
            }
            playback.error != null -> Surface(shape = RoundedCornerShape(17.dp), color = V2.Card) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (ar) "الفيديو ما اشتغلش" else "Couldn’t play this video", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { player.prepare(); player.play() }) { Text(if (ar) "حاول تاني" else "Retry", color = V2.Mint) }
                }
            }
            chrome -> PlayerTransport(ar, playback.active, playback.ended, playback.seekable, playback.buffering,
                onBackTen = { jump(-10_000L) }, onToggle = ::toggle, onForwardTen = { jump(10_000L) })
            playback.buffering -> CircularProgressIndicator(Modifier.size(36.dp), color = V2.Mint, strokeWidth = 2.dp)
        }
        if (!pip) notice?.let { message ->
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = V2.Card, shape = RoundedCornerShape(14.dp)) {
                Text(message, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, captions = {
        if (!pip && subMode != SubMode.OFF && playback.error == null) PlayerCaptionLines(
            if (subMode != SubMode.TRANSLATED) SrtCodec.cueAt(cues, at) else null,
            if (subMode != SubMode.ORIGINAL) SrtCodec.cueAt(cues, at, translated = true) else null, subSize)
    }, deck = { compact ->
        PlayerDeck(ar, compact, clock, playback.duration,
            if (playback.duration > 0) (buffered.toDouble() / playback.duration).toFloat() else 0f,
            playback.seekable && playback.duration > 0 && playback.error == null, playback.live, rateLabel(rate), muted,
            cues.isNotEmpty(), subMode != SubMode.OFF, landscape, onScrub = { scrub = it }, onScrubEnd = {
                scrub?.let { seek((it * playback.duration).toLong()) }; scrub = null
            }, onSpeed = { panel = Panel.SPEED }, onMute = ::onMute, onCaptions = { panel = Panel.CAPTIONS }, onRotate = ::rotate)
    })

    val dismiss: () -> Unit = { panel = null; activityTick++ }
    val localFile = remember(video?.localPath) { video?.let { File(it.localPath).isFile && it.kind != MediaKind.HLS } == true }
    val canSnapshot = localFile && (kind?.isCompleteVideo == true || kind == MediaKind.TS)
    if (!pip) when (panel) {
        Panel.TOOLS -> {
            val off = if (ar) "متوقف" else "Off"
            val on = if (ar) "مفعّل" else "On"
            PlayerTools(ar, listOfNotNull(
                PlayerTool(Icons.Filled.Speed, if (ar) "سرعة التشغيل" else "Playback speed", rateLabel(rate)) { panel = Panel.SPEED },
                PlayerTool(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (ar) "كتم الصوت" else "Mute", if (muted) on else off) { onMute(); dismiss() },
                PlayerTool(Icons.Filled.ClosedCaption, if (ar) "الترجمة" else "Captions",
                    if (cues.isNotEmpty()) (if (ar) subMode.ar else subMode.en) else (if (ar) "مفيش ترجمة من Video2 للملف ده" else "No Video2 captions for this file"),
                    cues.isNotEmpty()) { panel = Panel.CAPTIONS },
                PlayerTool(Icons.Filled.Search, if (ar) "بحث في الترجمة" else "Search captions", enabled = playback.seekable) { panel = Panel.SEARCH }.takeIf { cues.isNotEmpty() },
                PlayerTool(Icons.Filled.RecordVoiceOver, if (ar) "الدبلجة" else "Dubbed audio", if (dubOn) on else off) { dubOn = !dubOn; dismiss() }.takeIf { canDub },
                PlayerTool(Icons.Filled.Repeat, if (ar) "تكرار الفيديو" else "Repeat video", if (loop) on else off) {
                    loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; dismiss()
                },
                PlayerTool(Icons.Filled.Timer, if (ar) "مؤقت النوم" else "Sleep timer", if (deadline == 0L) off else on) { panel = Panel.SLEEP },
                PlayerTool(Icons.Filled.AspectRatio, if (ar) "ملء الشاشة بالقص" else "Zoom to fill", if (zoomed) on else off) { zoomed = !zoomed; dismiss() },
                PlayerTool(Icons.Filled.ScreenRotation, if (landscape) (if (ar) "عرض رأسي" else "Portrait") else (if (ar) "عرض أفقي" else "Landscape")) { dismiss(); rotate() },
                PlayerTool(Icons.Filled.PictureInPictureAlt, if (ar) "صورة داخل صورة" else "Picture-in-picture", enabled = playback.error == null) { dismiss(); enterPip() }.takeIf { canPip },
                PlayerTool(Icons.Filled.Lock, if (ar) "اقفل أدوات التشغيل" else "Lock controls") { dismiss(); locked = true },
                PlayerTool(Icons.Filled.PhotoCamera, if (ar) "احفظ لقطة" else "Save snapshot") {
                    dismiss()
                    snapshotAt = player.currentPosition
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) snapshot(snapshotAt) else createSnapshot.launch(snapshotName())
                }.takeIf { canSnapshot },
                PlayerTool(Icons.Filled.Share, if (ar) "شارك الملف" else "Share file") {
                    dismiss()
                    if (video != null && !sharePlayerFile(activity, video)) notice = if (ar) "المشاركة مش متاحة للملف ده" else "Couldn’t share this file"
                }.takeIf { localFile },
            ), dismiss)
        }
        Panel.SPEED -> PlayerChoices(ar, if (ar) "سرعة التشغيل" else "Playback speed", listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), rate,
            { if (it == 1f) { if (ar) "عادي · 1×" else "Normal · 1×" } else rateLabel(it) }, dismiss) { newRate ->
            player.setPlaybackSpeed(newRate); rate = newRate; dismiss()
        }
        Panel.SLEEP -> PlayerChoices(ar, if (ar) "مؤقت النوم" else "Sleep timer", listOf(0, 15, 30, 45, 60), 0.takeIf { deadline == 0L },
            { if (it == 0) (if (ar) "إلغاء المؤقت" else "Turn timer off") else if (ar) "$it دقيقة" else "$it minutes" }, dismiss) { minutes ->
            deadline = if (minutes == 0) 0L else SystemClock.elapsedRealtime() + minutes * 60_000L
            notice = if (minutes == 0) (if (ar) "المؤقت اتلغى" else "Sleep timer cancelled") else
                (if (ar) "التشغيل هيقف بعد $minutes دقيقة" else "Pausing in $minutes minutes")
            dismiss()
        }
        Panel.CAPTIONS -> PlayerCaptionsSettings(ar, captionModes, subMode, subSize, { chosenMode = it }, { subSize = it }, dismiss)
        Panel.SEARCH -> PlayerCaptionSearch(ar, cues, dismiss) { cue -> seek((cue.startSec * 1_000).toLong()); dismiss() }
        null -> Unit
    }
}
