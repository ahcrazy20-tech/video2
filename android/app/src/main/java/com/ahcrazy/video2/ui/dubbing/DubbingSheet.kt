package com.ahcrazy.video2.ui.dubbing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ahcrazy.video2.core.dubbing.DubProvider
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.DubVoices
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_CUES
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SECONDS
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SPEAKERS
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.dubbing.AndroidDubEngine
import com.ahcrazy.video2.data.dubbing.DubRenderReport
import com.ahcrazy.video2.data.dubbing.DubSelection
import com.ahcrazy.video2.data.dubbing.DubSource
import com.ahcrazy.video2.data.dubbing.GeminiDubApi
import com.ahcrazy.video2.data.dubbing.GeminiDubMedia
import com.ahcrazy.video2.data.dubbing.GeminiDubSession
import com.ahcrazy.video2.data.dubbing.GeminiStage
import com.ahcrazy.video2.data.dubbing.analyzeGeminiDub
import com.ahcrazy.video2.data.dubbing.auditionDub
import com.ahcrazy.video2.data.dubbing.loadAzureDubConfig
import com.ahcrazy.video2.data.dubbing.loadDubSource
import com.ahcrazy.video2.data.dubbing.loadGeminiDubConfig
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

private data class TightCueChoice(val line: Int, val answer: CompletableDeferred<Boolean>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DubbingSheet(video: SavedVideo, ar: Boolean, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val ctx = activity.applicationContext
    val app = activity.app()
    val library = app.library
    val scope = rememberCoroutineScope()
    var loadVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var sourceVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var source by remember { mutableStateOf<DubSource?>(null) }
    var catalog by remember { mutableStateOf<DubVoices?>(null) }
    var azureConfigured by remember { mutableStateOf(false) }
    var geminiConfigured by remember { mutableStateOf(false) }
    var geminiMedia by remember { mutableStateOf<GeminiDubMedia?>(null) }
    var geminiInputError by remember { mutableStateOf<String?>(null) }
    var geminiSession by remember { mutableStateOf<GeminiDubSession?>(null) }
    var geminiStage by remember { mutableStateOf<GeminiStage?>(null) }
    var geminiRendering by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var androidError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<DubNotice?>(null) }
    var preview by remember { mutableStateOf(DubPreview.IDLE) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<DubProgress?>(null) }
    var renderJob by remember { mutableStateOf<Job?>(null) }
    var tightCue by remember { mutableStateOf<TightCueChoice?>(null) }

    fun reload() {
        previewJob?.cancel()
        notice = null
        geminiSession = null
        loading = true
        loadVersion++
    }
    LaunchedEffect(video.id, loadVersion) {
        previewJob?.join()
        val current = library.byId(video.id)
        coroutineScope {
            val subs = async { dubResult { loadDubSource(current ?: throw DubFailure(DubIssue.SOURCE_CHANGED)) } }
            val engine = async { dubResult { AndroidDubEngine.use(ctx) { it.catalog() } } }
            val azure = async { dubResult { loadAzureDubConfig(app.settings) }.isSuccess }
            val gemini = async { dubResult { loadGeminiDubConfig(app.settings) }.isSuccess }
            val subtitles = subs.await()
            val captions = subtitles.getOrNull()
            val media = if (current == null || captions == null) null else dubResult {
                withContext(Dispatchers.IO) { GeminiDubMedia.from(current, captions) }
            }
            val voices = engine.await()
            sourceVideo = current
            source = captions
            catalog = voices.getOrNull()
            azureConfigured = azure.await()
            geminiConfigured = gemini.await()
            geminiMedia = media?.getOrNull()
            geminiInputError = media?.exceptionOrNull()?.dubMessage(ar)
            sourceError = subtitles.exceptionOrNull()?.dubMessage(ar)
            androidError = voices.exceptionOrNull()?.dubMessage(ar)
            loading = false
        }
    }
    suspend fun selection(voice: DubVoice, engine: String?): DubSelection = when (voice.provider) {
        DubProvider.ANDROID -> DubSelection.Device(voice, engine)
        DubProvider.AZURE -> DubSelection.Azure(voice, loadAzureDubConfig(app.settings))
        DubProvider.GEMINI -> throw DubFailure(DubIssue.GEMINI_SETUP)
    }
    suspend fun geminiSelection(captions: DubSource, options: GeminiOptions): DubSelection {
        if (!options.consented) throw DubFailure(DubIssue.GEMINI_CONSENT)
        val media = geminiMedia ?: throw DubFailure(DubIssue.GEMINI_LOCAL)
        val api = GeminiDubApi(loadGeminiDubConfig(app.settings))
        val session = geminiSession ?: try {
            analyzeGeminiDub(ctx.cacheDir, captions, media, api, options) { stage ->
                withContext(Dispatchers.Main.immediate) { geminiStage = stage }
            }.also { geminiSession = it }
        } finally { geminiStage = null }
        return DubSelection.Gemini(session, api, options)
    }
    DisposableEffect(activity) {
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                previewJob?.cancel()
                if (geminiRendering) renderJob?.cancel()
            }
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

    fun startPreview(pick: suspend (DubSource) -> DubSelection) {
        val sampleSource = source ?: return
        if (loading || preview != DubPreview.IDLE || progress != null) return
        notice = null
        preview = DubPreview.PREPARING
        previewJob = scope.launch {
            val sample = File(ctx.cacheDir, "kadr-preview-${UUID.randomUUID()}.wav")
            try {
                auditionDub(ctx, sampleSource, pick(sampleSource), sample)
                preview = DubPreview.PLAYING
                playPreview(ctx, sample)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = DubNotice(e.dubMessage(ar), failed = true) }
            finally { sample.delete(); preview = DubPreview.IDLE }
        }
    }

    fun startSave(gemini: Boolean = false, pick: suspend (DubSource) -> DubSelection) {
        val snapshot = sourceVideo ?: return
        val captions = source ?: return
        if (loading || progress != null || (gemini && preview != DubPreview.IDLE)) return
        val pending = previewJob
        pending?.cancel()
        notice = null
        progress = DubProgress(0, captions.plan.lines.size)
        geminiRendering = gemini
        renderJob = scope.launch {
            try {
                pending?.join()
                var skipRemaining = false
                val report = saveDub(ctx, library, snapshot, captions, pick(captions), onUnfittable = { line ->
                    withContext(Dispatchers.Main.immediate) {
                        if (skipRemaining) true else {
                            val choice = TightCueChoice(line, CompletableDeferred())
                            tightCue = choice
                            try { choice.answer.await().also { skipRemaining = it } }
                            finally { tightCue = null }
                        }
                    }
                }) { done, total ->
                    withContext(Dispatchers.Main.immediate) { progress = DubProgress(done, total) }
                }
                notice = DubNotice(report.savedMessage(ar))
            } catch (e: CancellationException) {
                notice = DubNotice(if (ar) "توليد الدبلجة اتوقف." else "Dubbing stopped.")
                throw e
            } catch (e: Exception) { notice = DubNotice(e.dubMessage(ar), failed = true) }
            finally { progress = null; geminiRendering = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V2.Card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DubbingPanel(video.title, ar, DubbingPanelState(source, catalog, loading, listOfNotNull(sourceError),
            preview, progress, notice, azureConfigured, androidError,
            GeminiPanelState(geminiConfigured, geminiInputError, geminiSession?.direction, geminiStage)),
            onPreview = { voice ->
                val engine = catalog?.preferredEngine
                startPreview { selection(voice, engine) }
            },
            onStopPreview = { previewJob?.cancel() },
            onSave = { voice ->
                val engine = catalog?.preferredEngine
                startSave { selection(voice, engine) }
            },
            onCancelSave = { renderJob?.cancel() },
            onChoiceChanged = { previewJob?.cancel(); notice = null },
            onRefresh = ::reload,
            onSettings = { openSettings(false) }, onInstall = { openSettings(true) },
            onAzureSettings = onOpenSettings, onGeminiSettings = onOpenSettings,
            onGeminiPreview = { options -> startPreview { geminiSelection(it, options) } },
            onGeminiSave = { options -> startSave(gemini = true) { geminiSelection(it, options) } },
            onGeminiDirection = { direction -> geminiSession = geminiSession?.withDirection(direction); notice = null })
        tightCue?.let { choice -> TightCueDialog(ar, choice.line) { choice.answer.complete(it) } }
    }
}

@Composable
internal fun TightCueDialog(ar: Boolean, line: Int, onDecision: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = { onDecision(false) },
        title = { Text(if (ar) "السطر $line أطول من وقته" else "Cue $line is too long") },
        text = { Text(if (ar) "الصوت مش هيدخل حتى بعد استغلال الفراغ. تقدر تكمّل وتسيب السطور اللي مش هتدخل من غير دبلجة. توقيت باقي السطور وكلام الترجمة مش هيتغيّروا."
            else "Even with the nearby gap, the speech won't fit. You can continue and leave unfittable cues undubbed. Other cue timings and saved subtitles won't change.") },
        confirmButton = { TextButton(onClick = { onDecision(true) }) { Text(if (ar) "كمّل وتخطّى" else "Continue and skip") } },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text(if (ar) "وقّف" else "Stop") } })
}

private fun DubRenderReport.savedMessage(ar: Boolean): String {
    if (skipped.isEmpty()) return if (ar) "الدبلجة اتحفظت. ارجع للمكتبة وشغّل الفيديو." else "Dub saved. Play the video from your library."
    val numbers = skipped.take(8).joinToString(", ") + if (skipped.size > 8) "…" else ""
    return if (ar) "الدبلجة اتحفظت، و${skipped.size} سطر من غير دبلجة بسبب ضيق التوقيت: $numbers."
        else "Dub saved. ${skipped.size} cues left undubbed because they did not fit: $numbers."
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
        DubIssue.CUE_TOO_LONG -> if (ar) "الكلام أطول من الوقت المتاح. جرّب صوت تاني؛ مفيش كلام اتقصّ." else "This voice can't fit the text in its cue. Try another voice; speech wasn't cut off."
        DubIssue.AUDIO_FORMAT -> if (ar) "تعذّر قراءة أو تشغيل الصوت اللي المحرّك ولّده." else "Couldn't read or play the audio produced by the engine."
        DubIssue.DISK -> if (ar) "المساحة مش كفاية أو ملف الدبلجة ما اتحفظش." else "Not enough space, or the dub couldn't be saved."
        DubIssue.SOURCE_CHANGED -> if (ar) "الفيديو أو الـSRT اتغيّر. حدّث الشاشة وجرّب تاني." else "The video or subtitle file changed. Refresh and try again."
        DubIssue.SYNTHESIS -> if (ar) "المحرّك ما قدرش يولّد الصوت. جرّب صوت تاني أو حدّث القائمة." else "The engine couldn't synthesize audio. Try another voice or refresh."
        DubIssue.AZURE_SETUP -> if (ar) "راجع مفتاح Azure Speech ومنطقة المورد في الإعدادات." else "Check the Azure Speech key and resource region in Settings."
        DubIssue.AZURE_AUTH -> if (ar) "Azure رفض المفتاح. اتأكد إنه مفتاح مورد Speech وإن المنطقة مطابقة." else "Azure refused the key. Check that it belongs to a Speech resource in the selected region."
        DubIssue.AZURE_LIMIT -> if (ar) "Azure وصل لحد الطلبات أو الحصة. جرّب بعد شوية أو راجع حسابك." else "Azure hit a request limit or quota. Try later or check your account."
        DubIssue.AZURE_SERVICE -> if (ar) "Azure ما قدرش يولّد الصوت. راجع المنطقة أو جرّب صوت تاني بعد شوية." else "Azure couldn't synthesize this voice. Check the region or try again with a different voice."
        DubIssue.AZURE_NETWORK -> if (ar) "ما قدرناش نتصل بـ Azure. راجع النت وجرّب المعاينة تاني." else "Couldn't connect to Azure. Check your internet connection and preview again."
        DubIssue.GEMINI_SETUP -> if (ar) "أضف مفتاح Gemini من Google AI Studio في الإعدادات ← مفاتيح الـAPI." else "Add a Google AI Studio key under Settings → API keys → Gemini."
        DubIssue.GEMINI_CONSENT -> if (ar) "اسمح بإرسال الصوت الأصلي للتحليل والنص للتوليد الأول." else "Allow both original-audio analysis and subtitle-text synthesis first."
        DubIssue.GEMINI_LOCAL -> if (ar) "Gemini محتاج ملف صوت أو فيديو محلي، مش رابط أو قائمة بث. جرّب ملف MP4 محفوظ." else "Gemini needs a local audio or video file, not a stream or playlist. Try a saved MP4."
        DubIssue.GEMINI_SIZE -> if (ar) "دبلجة Gemini التجريبية تدعم لحد ${GEMINI_MAX_SECONDS / 60} دقايق و$GEMINI_MAX_CUES سطر. جرّب مقطع أقصر أو صوت Android/Azure."
            else "Experimental Gemini dubbing supports up to ${GEMINI_MAX_SECONDS / 60} minutes and $GEMINI_MAX_CUES cues. Try a shorter clip or Android/Azure."
        DubIssue.GEMINI_AUDIO -> if (ar) "ما قدرناش نقرأ مسار الصوت الأصلي على الجهاز. جرّب ملف MP4 فيه صوت شغّال." else "Couldn't decode the original audio track on-device. Try an MP4 with playable audio."
        DubIssue.GEMINI_CAST -> if (ar) "Gemini رجّع أكتر من $GEMINI_MAX_SPEAKERS متحدثين. جرّب مشهد أقصر." else "Gemini returned more than $GEMINI_MAX_SPEAKERS speakers. Try a shorter scene."
        DubIssue.GEMINI_ANALYSIS -> if (ar) "Gemini ما رجّعش تحليل صالح للمشهد. جرّب حوار أوضح واتأكد إن توقيت الـSRT ماشي مع الصوت."
            else "Gemini didn't return a usable scene analysis. Try clearer dialogue and check that the SRT timing matches the audio."
        DubIssue.GEMINI_AUTH -> if (ar) "Gemini رفض مفتاح الـAPI أو صلاحيات المشروع. راجع المفتاح وإعدادات مشروعه في Google AI Studio."
            else "Gemini refused the API key or project access. Check your key and project in Google AI Studio."
        DubIssue.GEMINI_LIMIT -> if (ar) "وصلت لحد طلبات أو حصة Gemini API. راجع حدود مشروع المفتاح في AI Studio وجرّب بعدين."
            else "Gemini API hit a rate or quota limit. Check the key's project limits in AI Studio and try again later."
        DubIssue.GEMINI_MODEL -> if (ar) "موديل الدبلجة مش متاح لمشروع المفتاح. راجع الإتاحة في Google AI Studio." else "A dubbing model is unavailable to this API project. Check availability in Google AI Studio."
        DubIssue.GEMINI_SERVICE -> if (ar) "Gemini ما قدرش يكمل الطلب. جرّب معاينة مقطع أقصر أو ارجع بعد شوية." else "Gemini couldn't complete the request. Try a shorter clip or try again later."
        DubIssue.GEMINI_NETWORK -> if (ar) "الاتصال بـ Gemini اتقطع أو الطلب أخد وقت طويل. راجع النت وجرّب تاني." else "The Gemini connection failed or timed out. Check your internet and try again."
        DubIssue.GEMINI_BLOCKED -> if (ar) "Gemini ما رجّعش نتيجة بسبب فلترة المحتوى. الدبلجة القديمة ما اتغيّرتش." else "Gemini blocked the request. The previous dub hasn't changed."
        null -> if (this is IOException) {
            if (ar) "حصل خطأ في قراءة أو حفظ الملف. راجع المساحة المتاحة." else "Couldn't read or save a file. Check available storage."
        } else if (ar) "حصل خطأ في الدبلجة. حدّث القائمة وجرّب تاني." else "Dubbing failed. Refresh and try again."
    }
    return failure?.line?.let { (if (ar) "السطر $it: " else "Cue $it: ") + text } ?: text
}
