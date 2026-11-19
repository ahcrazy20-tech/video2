package com.ahcrazy.video2.ui.dubbing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubVoices
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.dubbing.AndroidDubEngine
import com.ahcrazy.video2.data.dubbing.DubSource
import com.ahcrazy.video2.data.dubbing.auditionDub
import com.ahcrazy.video2.data.dubbing.loadDubSource
import com.ahcrazy.video2.data.dubbing.saveDub
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DubbingSheet(video: SavedVideo, ar: Boolean, onDismiss: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val ctx = activity.applicationContext
    val library = activity.app().library
    val scope = rememberCoroutineScope()
    var loadVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var sourceVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var source by remember { mutableStateOf<DubSource?>(null) }
    var catalog by remember { mutableStateOf<DubVoices?>(null) }
    var loadErrors by remember { mutableStateOf(emptyList<String>()) }
    var notice by remember { mutableStateOf<DubNotice?>(null) }
    var preview by remember { mutableStateOf(DubPreview.IDLE) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<DubProgress?>(null) }
    var renderJob by remember { mutableStateOf<Job?>(null) }

    fun reload() {
        previewJob?.cancel()
        notice = null
        loading = true
        loadVersion++
    }
    LaunchedEffect(video.id, loadVersion) {
        previewJob?.join()
        val current = library.byId(video.id)
        val (subtitles, voices) = coroutineScope {
            val subs = async { dubResult { loadDubSource(current ?: throw DubFailure(DubIssue.SOURCE_CHANGED)) } }
            val engine = async { dubResult { AndroidDubEngine.use(ctx) { it.catalog() } } }
            subs.await() to engine.await()
        }
        sourceVideo = current
        source = subtitles.getOrNull()
        catalog = voices.getOrNull()
        loadErrors = listOfNotNull(subtitles.exceptionOrNull()?.dubMessage(ar), voices.exceptionOrNull()?.dubMessage(ar))
        loading = false
    }
    DisposableEffect(activity) {
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) previewJob?.cancel()
        }
        activity.lifecycle.addObserver(lifecycle)
        onDispose { activity.lifecycle.removeObserver(lifecycle) }
    }

    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload() }
    fun openSettings(install: Boolean) {
        previewJob?.cancel()
        if (install) catalog?.preferredEngine?.let { pkg ->
            try {
                systemSettings.launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) { /* Not all engines expose a data installer. */ }
            catch (_: SecurityException) { /* Open the system settings instead. */ }
        }
        try {
            systemSettings.launch(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            notice = DubNotice(if (ar) "افتح إعدادات الموبايل وابحث عن «تحويل النص إلى كلام»." else "Search phone settings for ‘Text-to-speech output’.")
        } catch (_: SecurityException) {
            notice = DubNotice(if (ar) "افتح إعدادات الموبايل وابحث عن «تحويل النص إلى كلام»." else "Search phone settings for ‘Text-to-speech output’.")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V2.Card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DubbingPanel(video.title, ar, DubbingPanelState(source, catalog, loading, loadErrors, preview, progress, notice),
            onPreview = listen@{ voice ->
                val sampleSource = source ?: return@listen
                if (previewJob?.isActive == true || renderJob?.isActive == true) return@listen
                val engine = catalog?.preferredEngine
                notice = null
                preview = DubPreview.PREPARING
                previewJob = scope.launch {
                    val sample = File(ctx.cacheDir, "kadr-preview-${UUID.randomUUID()}.wav")
                    try {
                        auditionDub(ctx, sampleSource, voice, engine, sample)
                        preview = DubPreview.PLAYING
                        playPreview(ctx, sample)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = DubNotice(e.dubMessage(ar), failed = true) }
                    finally { sample.delete(); preview = DubPreview.IDLE }
                }
            },
            onStopPreview = { previewJob?.cancel() },
            onSave = save@{ voice ->
                val snapshot = sourceVideo ?: return@save
                val captions = source ?: return@save
                if (renderJob?.isActive == true) return@save
                val engine = catalog?.preferredEngine
                val pending = previewJob
                pending?.cancel()
                notice = null
                progress = DubProgress(0, captions.plan.lines.size)
                renderJob = scope.launch {
                    try {
                        pending?.join()
                        saveDub(ctx, library, snapshot, captions, voice, engine) { done, total ->
                            withContext(Dispatchers.Main.immediate) { progress = DubProgress(done, total) }
                        }
                        notice = DubNotice(if (ar) "الدبلجة اتحفظت. ارجع للمكتبة وشغّل الفيديو." else "Dub saved. Play the video from your library.")
                    } catch (e: CancellationException) {
                        notice = DubNotice(if (ar) "توليد الدبلجة اتوقف." else "Dubbing stopped.")
                        throw e
                    } catch (e: Exception) { notice = DubNotice(e.dubMessage(ar), failed = true) }
                    finally { progress = null }
                }
            },
            onCancelSave = { renderJob?.cancel() },
            onChoiceChanged = { previewJob?.cancel(); notice = null },
            onRefresh = ::reload,
            onSettings = { openSettings(false) }, onInstall = { openSettings(true) })
    }
}

private suspend fun playPreview(context: Context, file: File) {
    val ended = CompletableDeferred<Boolean>()
    val player = ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true).build()
    try {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(true)
            }
            override fun onPlayerError(error: PlaybackException) { ended.complete(false) }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
        if (withTimeoutOrNull(25_000) { ended.await() } != true) throw DubFailure(DubIssue.AUDIO_FORMAT)
    } finally { player.release() }
}

private suspend fun <T> dubResult(action: suspend () -> T): Result<T> = try { Result.success(action()) }
catch (e: CancellationException) { throw e }
catch (e: Exception) { Result.failure(e) }

private fun Throwable.dubMessage(ar: Boolean): String {
    val failure = this as? DubFailure
    val text = when (failure?.issue) {
        DubIssue.NO_SUBTITLES -> if (ar) "الفيديو محتاج ترجمة محفوظة أو نص أصلي محفوظ (SRT) الأول." else "This video needs a saved subtitle file (SRT) first."
        DubIssue.TIMING -> if (ar) "فيه توقيت غير صالح في ملف الـSRT." else "The subtitle file has an invalid timestamp."
        DubIssue.TOO_LARGE -> if (ar) "المدة أو ملف الـSRT أكبر من الحد المدعوم للدبلجة." else "The duration or subtitle file exceeds the dubbing limit."
        DubIssue.NO_ENGINE -> if (ar) "محرّك النطق ما اشتغلش. راجع إعدادات النطق وحدّث القائمة." else "The speech engine didn't initialize. Check speech settings and refresh."
        DubIssue.VOICE_CHANGED -> if (ar) "الصوت المختار بقى مش متاح. حدّث القائمة وجرّب المعاينة تاني." else "The selected voice is no longer available. Refresh the list and preview again."
        DubIssue.NEEDS_DOWNLOAD -> if (ar) "نزّل بيانات الصوت من إعدادات النطق وارجع حدّث القائمة." else "Download the voice data in speech settings, then refresh."
        DubIssue.NEEDS_NETWORK -> if (ar) "المحرّك طلب اتصال بالإنترنت أو الاتصال فشل. جرّب صوت على الجهاز." else "The engine needed a network connection or lost it. Try an on-device voice."
        DubIssue.CUE_TOO_LONG -> if (ar) "الكلام أطول من الوقت المتاح للصوت ده. جرّب صوت تاني؛ التطبيق مش هيقص الكلام." else "This voice can't fit the text in its cue. Try another voice; speech won't be cut off."
        DubIssue.AUDIO_FORMAT -> if (ar) "تعذّر قراءة أو تشغيل الصوت اللي المحرّك ولّده." else "Couldn't read or play the audio produced by the engine."
        DubIssue.DISK -> if (ar) "المساحة مش كفاية أو ملف الدبلجة ما اتحفظش." else "Not enough space, or the dub couldn't be saved."
        DubIssue.SOURCE_CHANGED -> if (ar) "الفيديو أو الـSRT اتغيّر. حدّث الشاشة وجرّب تاني." else "The video or subtitle file changed. Refresh and try again."
        DubIssue.SYNTHESIS -> if (ar) "المحرّك ما قدرش يولّد الصوت. جرّب صوت تاني أو حدّث القائمة." else "The engine couldn't synthesize audio. Try another voice or refresh."
        null -> if (this is IOException) {
            if (ar) "حصل خطأ في قراءة أو حفظ الملف. راجع المساحة المتاحة." else "Couldn't read or save a file. Check available storage."
        } else if (ar) "حصل خطأ في الدبلجة. حدّث القائمة وجرّب تاني." else "Dubbing failed. Refresh and try again."
    }
    return failure?.line?.let { (if (ar) "السطر $it: " else "Cue $it: ") + text } ?: text
}
