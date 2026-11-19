package com.ahcrazy.video2.ui.translate

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.Video2Application
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.OfflinePreset
import com.ahcrazy.video2.core.model.RefinerProvider
import com.ahcrazy.video2.core.model.STTProvider
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.core.model.TranslationMode
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.offline.OfflineIssue
import com.ahcrazy.video2.data.offline.OfflineModel
import com.ahcrazy.video2.data.offline.OfflineWorkKind
import com.ahcrazy.video2.data.offline.RETIRED_TRANSLATOR_ID
import com.ahcrazy.video2.data.offline.supportsLocalPair
import com.ahcrazy.video2.data.offline.supportsSilenceGate
import com.ahcrazy.video2.ui.components.ChipPreview
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.PickerRow
import com.ahcrazy.video2.ui.components.SectionTitle
import com.ahcrazy.video2.ui.theme.V2
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(ar: Boolean, preselected: SavedVideo?, onConsumePreselected: () -> Unit,
                    onReviewJob: (TranslationJob) -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    val jobs by app.translations.jobs.collectAsState()
    val videos by app.library.videos.collectAsState()
    val localWork by app.offlineWork.status.collectAsState()
    val installed by app.offlineModels.models.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var performanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionId by rememberSaveable { mutableStateOf<String?>(null) }
    var retryMoonshineId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedTranslator by remember { mutableStateOf(OfflineModel.HYMT_LITE) }

    androidx.compose.runtime.LaunchedEffect(preselected) {
        if (preselected != null) { retryMoonshineId = null; showNew = true }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ar) "الترجمة والدبلجة" else "Translate & Dub", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "تفريغ صوتي وترجمة للفيديوهات المحفوظة · ٣ ملفات SRT" else "Transcription and translation for saved videos · 3 SRT files", fontSize = 11.sp, color = V2.TextDim)
            }
            FloatingActionButton(onClick = { retryMoonshineId = null; showNew = true }, containerColor = V2.Action, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), emphasized = true) {
            Column {
                ChipPreview(if (ar) "معالجة أوفلاين" else "OFFLINE WORKSPACE", V2.Mint)
                Text(if (ar) "ترجمة على الجهاز" else "On-device subtitles", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text(if (ar) "نزّل الموديلات مرة واحدة. بعدها التفريغ والترجمة من غير إنترنت أو مفاتيح API." else
                    "Install the models once. Then transcribe and translate without internet or API keys.",
                    fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { requestedTranslator = OfflineModel.HYMT_LITE; showModels = true }) {
                    Text(if (localWork?.kind in setOf(OfflineWorkKind.DOWNLOAD, OfflineWorkKind.MODEL_BACKUP, OfflineWorkKind.MODEL_RESTORE)) {
                        val percent = ((localWork?.progress ?: 0.0) * 100).toInt()
                        if (ar) "تجهيز الموديلات · $percent%" else "Preparing models · $percent%"
                    } else if (ar) "الموديلات المحلية" else "On-device models")
                }
            }
        }
        if (jobs.isEmpty()) EmptyState(Icons.Filled.ClosedCaption,
            if (ar) "مفيش مهام ترجمة" else "No translation jobs",
            if (ar) "دوس + واختار فيديو من المكتبة نبدأ بيه" else "Tap + and pick a library video")
        LazyColumn {
            items(jobs, key = { it.id }) { job ->
                val offline = job.mode == TranslationMode.ON_DEVICE
                val retired = offline && job.offlineModel == RETIRED_TRANSLATOR_ID
                val running = job.phase !in setOf(TranslationPhase.FAILED, TranslationPhase.PAUSED, TranslationPhase.CANCELLED, TranslationPhase.DONE)
                val stopping = offline && localWork?.key == job.id && localWork?.stopping == true
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(enabled = job.phase == TranslationPhase.DONE) { onReviewJob(job) }) {
                    Column {
                        Text(job.videoTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val engine = if (offline) {
                            val local = if (ar) "على الجهاز" else "On device"
                            val speech = OfflineModel.speechForJob(job.offlineSpeechModel)?.label
                            val model = when (val t = OfflineModel.translatorForJob(job.offlineModel)) {
                                OfflineModel.HYMT_LITE -> "Hy‑MT2 1.8B"
                                OfflineModel.HYMT_Q4 -> "Hy‑MT2 7B"
                                null -> "Q6".takeIf { retired }
                                else -> t.label
                            }
                            val gate = (if (ar) "Silero · تخطّي الصمت" else "Silero · skip quiet gaps").takeIf { job.offlineSilenceGate }
                            listOfNotNull(local, speech, (if (ar) "سريع" else "Fast").takeIf { job.offlineFastSpeech }, gate, model).joinToString(" · ")
                        } else STTProvider.of(job.sttProvider).let { if (ar) it.titleAr else it.titleEn }
                        Text("$engine → ${SubLang.of(job.targetLang).let { if (ar) it.nameAr else it.englishName }}",
                            fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else phaseText(job.phase, ar), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (job.phase == TranslationPhase.DONE) V2.Mint else if (job.phase == TranslationPhase.FAILED) V2.Danger else V2.Gold)
                            Spacer(Modifier.weight(1f))
                            Text("${(job.progress * 100).toInt()}%", fontSize = 11.sp, color = V2.TextDim)
                            if (job.cueCount > 0) Text(" · ${job.cueCount} " + if (ar) "سطر" else "lines", fontSize = 11.sp, color = V2.TextDim)
                        }
                        if (job.phase !in setOf(TranslationPhase.DONE)) LinearProgressIndicator(
                            progress = { job.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 7.dp), color = V2.Accent)
                        val retryMoonshine = app.translations.moonshineRetryIssue(job)
                        val issue = retryMoonshine ?: if (retired && job.phase != TranslationPhase.DONE) OfflineIssue.RETIRED_TRANSLATOR
                            else OfflineIssue.entries.firstOrNull { it.name == job.offlineIssue }
                        (issue?.text(ar) ?: job.message)?.let { Text(it, fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp)) }
                        if (issue == OfflineIssue.LANGUAGE && app.translations.canCorrectSource(job)) {
                            TextButton(enabled = localWork == null, onClick = { correctionId = job.id }) {
                                Text(if (ar) "راجع التفريغ وصحّح اللغة" else "Review transcript and correct language")
                            }
                        }
                        if (!running && issue != OfflineIssue.LANGUAGE) app.translations.alternateTranslator(job)?.let { alternate ->
                            val ready = installed[alternate]?.installed == true
                            val size = if (alternate == OfflineModel.HYMT_LITE) "1.8B" else "7B"
                            TextButton(enabled = localWork == null, onClick = {
                                if (ready) app.translations.retranslate(job.id) else { requestedTranslator = alternate; showModels = true }
                            }) {
                                val wasFirefox = OfflineModel.translatorForJob(job.offlineModel)?.isFirefox == true
                                Text(when {
                                    wasFirefox && ready -> if (ar) "سياق أدق · من نفس التفريغ (Hy‑MT2)" else "Context · same transcript (Hy‑MT2)"
                                    wasFirefox -> if (ar) "نزّل Hy‑MT2 للسياق · من غير إعادة التفريغ" else "Get Hy‑MT2 for context · no new transcription"
                                    ready -> if (ar) "ترجم بـ$size من التفريغ المحفوظ" else "Translate saved transcript with $size"
                                    else -> if (ar) "نزّل $size لإعادة الترجمة فقط" else "Get $size to retranslate without transcribing"
                                })
                            }
                        }
                        if (!running) app.translations.firefoxTranslator(job)?.let { route ->
                            val ready = route.requiredBundles.all { installed[it]?.installed == true }
                            TextButton(enabled = localWork == null, onClick = {
                                if (ready) app.translations.translateSavedWithFirefox(job.id)
                                else { requestedTranslator = route; showModels = true }
                            }) {
                                Text(when {
                                    ready && route.isFirefoxPivot -> if (ar) "سريع · من نفس التفريغ (عبر الإنجليزي)" else "Fast · same transcript (via English)"
                                    ready -> if (ar) "ترجم التفريغ المحفوظ بـFirefox" else "Translate saved transcript with Firefox"
                                    else -> if (ar) "نزّل Firefox لإعادة الترجمة فقط" else "Get Firefox to translate without transcribing"
                                })
                            }
                        }
                        if (offline && job.phase == TranslationPhase.DONE) PerformanceButton(job, ar) { performanceId = job.id }
                        Row {
                            if (job.phase == TranslationPhase.DONE) TextButton(onClick = { onReviewJob(job) }) { Text(if (ar) "مراجعة وتعديل" else "Review & edit") }
                            if (retryMoonshine != null && videos.any { it.id == job.videoId }) {
                                TextButton(enabled = localWork == null, onClick = { retryMoonshineId = job.id; showNew = true }) {
                                    Text(if (ar) "ابدأ مهمة جديدة بـWhisper" else "New job with Whisper")
                                }
                            } else if (retryMoonshine == null && !retired && issue !in setOf(OfflineIssue.LANGUAGE, OfflineIssue.FAST_PAIR) &&
                                job.phase in setOf(TranslationPhase.FAILED, TranslationPhase.PAUSED, TranslationPhase.CANCELLED)) {
                                TextButton(enabled = !offline || localWork == null, onClick = { app.translations.resume(job.id) }) { Text(if (ar) "استئناف" else "Resume") }
                            }
                            if (running) TextButton(enabled = !stopping, onClick = { app.translations.cancel(job.id) }) { Text(if (ar) "إيقاف" else "Stop") }
                            Spacer(Modifier.weight(1f))
                            if (!running) TextButton(onClick = { app.translations.delete(job.id) }) { Text(if (ar) "حذف" else "Delete", color = V2.Accent) }
                        }
                    }
                }
            }
        }
    }

    jobs.firstOrNull { it.id == correctionId }?.let { SourceLanguageSheet(it, app.translations, localWork != null, ar) { correctionId = null } }
    jobs.firstOrNull { it.id == performanceId }?.let { PerformanceSheet(it, ar) { performanceId = null } }
    if (showNew) {
        val retry = jobs.firstOrNull { it.id == retryMoonshineId }
        val selected = videos.firstOrNull { it.id == retry?.videoId } ?: preselected
        key(retry?.id) {
            NewTranslationSheet(app, ar, videos, selected, retry, onDismiss = {
                showNew = false
                retryMoonshineId = null
                onConsumePreselected()
            })
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }) {
        var speech by remember { mutableStateOf(app.offlineModels.defaultSpeech) }
        var model by remember(requestedTranslator) { mutableStateOf(requestedTranslator) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (ar) "الموديلات المحلية" else "On-device models", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ModelFolderPanel(app, ar)
            OfflineModelPanel(app, ar, speech, { speech = it }, model, { model = it })
            RetiredModelCleanup(app, ar, localWork == null)
            Spacer(Modifier.height(20.dp))
        }
    }

}

private fun phaseText(p: TranslationPhase, ar: Boolean) = when (p) {
    TranslationPhase.QUEUED -> if (ar) "في الطابور…" else "Queued…"
    TranslationPhase.PREPARING -> if (ar) "تجهيز…" else "Preparing…"
    TranslationPhase.EXTRACTING -> if (ar) "استخراج الصوت…" else "Extracting audio…"
    TranslationPhase.TRANSCRIBING -> if (ar) "جارٍ التفريغ…" else "Transcribing…"
    TranslationPhase.REFINING -> if (ar) "مراجعة وتدقيق النصوص…" else "Proofreading…"
    TranslationPhase.TRANSLATING -> if (ar) "جارٍ الترجمة…" else "Translating…"
    TranslationPhase.SAVING -> if (ar) "حفظ ملفات SRT…" else "Saving SRTs…"
    TranslationPhase.DONE -> if (ar) "اكتملت ✓" else "Done ✓"
    TranslationPhase.PAUSED -> if (ar) "متوقفة" else "Paused"
    TranslationPhase.CANCELLED -> if (ar) "اتلغت" else "Cancelled"
    TranslationPhase.FAILED -> if (ar) "فشلت" else "Failed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTranslationSheet(app: Video2Application, ar: Boolean, videos: List<SavedVideo>, preselected: SavedVideo?,
                                retryMoonshine: TranslationJob?, onDismiss: () -> Unit) {
    var videoId by remember(preselected) { mutableStateOf(preselected?.id ?: videos.firstOrNull()?.id ?: "") }
    var source by remember { mutableStateOf(if (retryMoonshine != null) SubLang.EN else SubLang.AUTO) }
    var target by remember { mutableStateOf(SubLang.of(retryMoonshine?.targetLang).takeUnless { it == SubLang.AUTO } ?: SubLang.AR) }
    var mode by remember { mutableStateOf(TranslationMode.ON_DEVICE) }
    var speech by remember { mutableStateOf(if (retryMoonshine != null) app.offlineModels.rapidWhisper else app.offlineModels.defaultSpeech) }
    var fastSpeech by remember { mutableStateOf(false) }
    var gateRequested by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(OfflinePreset.CPU) }
    var localTranslator by remember {
        mutableStateOf(OfflineModel.translatorForJob(retryMoonshine?.offlineModel) ?: app.offlineModels.defaultTranslator)
    }
    var stt by remember { mutableStateOf(STTProvider.AUTO) }
    var refiner by remember { mutableStateOf(RefinerProvider.AUTO) }
    var translator by remember { mutableStateOf(TranslatorProvider.AUTO) }
    val models by app.offlineModels.models.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val offline = mode == TranslationMode.ON_DEVICE
    val canGate = supportsSilenceGate(source.code, speech)
    val silenceGate = canGate && gateRequested
    val vulkan = remember(app) { app.offlineModels.supportsVulkan() } && !speech.isMoonshine
    val overlap = remember(app, speech, localTranslator) { app.offlineModels.supportsOverlap(speech, localTranslator) }
    val profiles = if (overlap) OfflinePreset.entries else if (vulkan) listOf(OfflinePreset.CPU, OfflinePreset.VULKAN) else listOf(OfflinePreset.CPU)
    val effective = preset.takeIf { it in profiles } ?: if (vulkan) OfflinePreset.VULKAN else OfflinePreset.CPU
    val video = videos.firstOrNull { it.id == videoId } ?: videos.firstOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (ar) "مهمة ترجمة جديدة" else "New translation job", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (retryMoonshine != null) Text(
                if (ar) "نفس الفيديو، بتفريغ Whisper جديد. المهمة القديمة وملفاتك مش هتتغيّر."
                else "Same video, new Whisper transcription. Your previous job and files stay unchanged.",
                color = V2.TextDim, fontSize = 12.sp)
            if (video == null) Text(if (ar) "المكتبة فاضية — حمل أو استورد فيديو الأول." else "Library is empty — download or import a video first.",
                color = V2.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            else {
                PickerRow(if (ar) "الفيديو" else "Video", video, videos, { it.title }) { videoId = it.id }
                PickerRow(if (ar) "وضع المعالجة" else "Processing", mode, listOf(TranslationMode.ON_DEVICE, TranslationMode.CLOUD), {
                    if (it == TranslationMode.ON_DEVICE) { if (ar) "على الجهاز" else "On device" } else { if (ar) "سحابي" else "Cloud" }
                }) { mode = it }
                SectionTitle(if (ar) "اللغات" else "Languages")
                PickerRow(if (ar) "لغة المصدر" else "Source", source, SubLang.entries, { if (ar) it.nameAr else it.englishName }) { source = it }
                PickerRow(if (ar) "لغة الهدف" else "Target", target, SubLang.entries.filter { it != SubLang.AUTO }, { if (ar) it.nameAr else it.englishName }) { target = it }
                val manifest = video.kind in setOf(MediaKind.HLS, MediaKind.DASH)
                val pairSupported = supportsLocalPair(source.code, target.code, speech, localTranslator)
                if (offline) {
                    SectionTitle(if (ar) "اختصارات السرعة" else "Speed presets")
                    val usesMoonshine = remember(app) { app.offlineModels.compatible(OfflineModel.MOONSHINE_EN) }
                    val rapidSpeech = if (usesMoonshine) OfflineModel.MOONSHINE_EN else remember(app, models) { app.offlineModels.rapidWhisper }
                    val rapidCompatible = remember(app, rapidSpeech) {
                        OfflineModel.pack(rapidSpeech, OfflineModel.FIREFOX_EN_AR).all(app.offlineModels::compatible)
                    }
                    OutlinedButton(onClick = {
                        source = SubLang.EN
                        target = SubLang.AR
                        speech = rapidSpeech
                        localTranslator = OfflineModel.FIREFOX_EN_AR
                        fastSpeech = false
                        gateRequested = false
                        preset = OfflinePreset.CPU
                    }, modifier = Modifier.fillMaxWidth(), enabled = work == null && rapidCompatible) {
                        Text(if (usesMoonshine) {
                            if (ar) "مسار سريع جديد · إنجليزي إلى عربي (تجريبي)" else "Rapid English → Arabic (experimental)"
                        } else {
                            if (ar) "سريع · إنجليزي إلى عربي (Whisper + Firefox)" else "Rapid English → Arabic (Whisper + Firefox)"
                        })
                    }
                    Text(if (usesMoonshine) {
                        if (ar) "Moonshine + Firefox Translations. للكلام الإنجليزي الواضح فقط؛ للعربي أو الكلام المختلط اختار Whisper وHy‑MT2. مفيش تنزيل تلقائي."
                        else "Moonshine + Firefox Translations for clear English speech. For Arabic or mixed speech, keep Whisper and Hy‑MT2. No automatic download."
                    } else if (app.offlineModels.moonshineIssue == OfflineIssue.MOONSHINE_CPU) {
                        if (ar) "Moonshine متوقف على المعالج ده لتفادي الكراش. المسار بيستخدم Whisper متوافق وبيستفيد من Firefox اللي نزّلته بالفعل."
                        else "Moonshine is disabled on this CPU to prevent a crash. This uses compatible Whisper and reuses your Firefox install."
                    } else {
                        if (ar) "Moonshine مش متاح. المسار بيستخدم Whisper وFirefox، من غير تنزيل تلقائي."
                        else "Moonshine is unavailable. This uses Whisper and Firefox, with no automatic downloads."
                    }, fontSize = 12.sp, color = V2.TextDim)
                    if (!rapidCompatible) Text(if (ar) "المسار الجديد محتاج أندرويد ٩ أو أحدث وجهاز ARM64 برام كافية."
                        else "The new route needs Android 9+ on ARM64 with enough RAM.", fontSize = 11.sp, color = V2.Gold)
                    SectionTitle(if (ar) "تجربة الإسباني والإيطالي" else "Spanish & Italian preview")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (language in listOf(SubLang.ES, SubLang.IT)) {
                            val route = checkNotNull(OfflineModel.firefoxFor(language.code, "ar"))
                            val usable = remember(app, route) {
                                OfflineModel.pack(OfflineModel.WHISPER_SMALL_Q5, route, silenceGate = true).all(app.offlineModels::compatible)
                            }
                            OutlinedButton(onClick = {
                                source = language
                                target = SubLang.AR
                                if (speech.isMoonshine || !app.offlineModels.ready(speech)) speech = OfflineModel.WHISPER_SMALL_Q5
                                localTranslator = route
                                fastSpeech = false
                                gateRequested = true
                                preset = OfflinePreset.CPU
                            }, modifier = Modifier.weight(1f), enabled = work == null && usable) {
                                Text(if (ar) "${language.nameAr} إلى عربي" else "${language.englishName} → Arabic")
                            }
                        }
                    }
                    val pivot = OfflineModel.firefoxFor(source.code, target.code)?.takeIf { it.isFirefoxPivot }
                    if (pivot != null && (localTranslator == pivot || !localTranslator.isFirefox)) {
                        PickerRow(if (ar) "أسلوب الترجمة" else "Translation style", localTranslator == pivot, listOf(true, false), {
                            if (it) { if (ar) "سريع · Firefox (عبر الإنجليزي)" else "Fast · Firefox (via English)" }
                            else { if (ar) "سياق أدق · Hy‑MT2" else "Dialogue context · Hy‑MT2" }
                        }) { fast -> localTranslator = if (fast) pivot else app.offlineModels.defaultTranslator }
                    }
                    fun fasterPath(model: OfflineModel, mt: OfflineModel) = when {
                        app.offlineModels.supportsOverlap(model, mt) -> OfflinePreset.OVERLAP
                        !model.isMoonshine && app.offlineModels.supportsVulkan() -> OfflinePreset.VULKAN
                        else -> OfflinePreset.CPU
                    }
                    OutlinedButton(onClick = {
                        fastSpeech = speech.supportsFastSpeech
                        preset = fasterPath(speech, localTranslator)
                    }, modifier = Modifier.fillMaxWidth(), enabled = work == null && (speech.supportsFastSpeech || vulkan)) {
                        Text(if (ar) "سرّع بنفس الموديلات" else "Speed up with the same models")
                    }
                    val smallCompatible = remember(app) {
                        OfflineModel.pack(OfflineModel.WHISPER_SMALL_Q5, OfflineModel.HYMT_LITE).all(app.offlineModels::compatible)
                    }
                    OutlinedButton(onClick = {
                        speech = OfflineModel.WHISPER_SMALL_Q5
                        localTranslator = OfflineModel.HYMT_LITE
                        fastSpeech = true
                        preset = fasterPath(speech, localTranslator)
                    }, modifier = Modifier.fillMaxWidth(), enabled = work == null && smallCompatible) {
                        Text(if (ar) "وضع سريع · كلام واضح" else "Fast preset · clear speech")
                    }
                    Text(if (ar) "الوضع السريع يختار Whisper Small (١٩٠ MB) مع 1.8B. الكلام المشوّش أو الصعب ممكن يحتاج Medium للدقة. الاختيار ما بينزّلش حاجة لوحده."
                        else "Fast selects Whisper Small (190 MB) with 1.8B. Use Medium for difficult or noisy speech. Nothing downloads automatically.",
                        color = V2.TextDim, fontSize = 12.sp)
                    if (canGate) {
                        PickerRow(if (ar) "تخطّي فواصل الصمت" else "Skip long quiet gaps", gateRequested, listOf(false, true), {
                            if (it) { if (ar) "Silero · تجريبي" else "Silero · experimental" }
                            else { if (ar) "إيقاف" else "Off" }
                        }) { gateRequested = it }
                        if (silenceGate) Text(if (ar) "يوفّر على Whisper الفواصل الهادية الطويلة. لو توقيت النص مش واضح، كادر يعيد الجزء كاملًا. الفيديو نفسه ما بيتقصّش."
                            else "Avoid long quiet gaps in Whisper. If cue timing is ambiguous, Kadr retries the full window. The video is never trimmed.",
                            fontSize = 11.sp, color = V2.TextDim)
                    }
                    OfflineModelPanel(app, ar, speech, { speech = it }, localTranslator, { localTranslator = it }, silenceGate)
                    if (speech.supportsFastSpeech) {
                        PickerRow(if (ar) "سرعة التفريغ" else "Transcription speed", fastSpeech, listOf(false, true), {
                            if (it) { if (ar) "سريع · للكلام الواضح" else "Fast · clear speech" }
                            else { if (ar) "دقة أعلى" else "Higher accuracy" }
                        }) { fastSpeech = it }
                    }
                    SectionTitle(if (ar) "مسار التشغيل والقياس" else "Execution & timing")
                    if (vulkan) PickerRow(if (ar) "التفريغ والترجمة" else "Execution path", effective, profiles, { it.title(ar) }) { preset = it }
                    else ChipPreview(OfflinePreset.CPU.title(ar), V2.Sky)
                    Text(if (speech.isMoonshine) {
                        if (ar) "Moonshine بيفرّغ أجزاء من الملف على CPU. التوقيت لكل جملة منطوقة، والإيقاف بعد الجزء الجاري."
                        else "Moonshine decodes bounded file windows on CPU. Timing is per utterance; Stop waits for the current native call."
                    } else if (!vulkan) {
                        if (ar) "Vulkan 1.2 مش متاح على الجهاز ده. التقرير هيقيس الـCPU." else "Vulkan 1.2 is unavailable. The report measures CPU."
                    } else if (effective == OfflinePreset.CPU) {
                        if (ar) "جرّب نفس الفيديو تاني بمسار GPU عشان تقارن التقريرين." else "Run the same clip with GPU to compare reports."
                    } else if (effective == OfflinePreset.OVERLAP) {
                        if (ar) "تجريبي: الترجمة على CPU بتبدأ بعد أول سطور ثابتة وهو لسه بيفرّغ على Vulkan. لو الرام أو الحرارة ما سمحتش، يكمل بالتتابع."
                        else "Experimental: CPU translation starts as stable cues become available during Vulkan speech decoding. Falls back to sequential work under pressure."
                    } else {
                        if (ar) "تجريبي: Whisper على Vulkan والمترجم على CPU. لو الجهاز ماقدرش، التفريغ يكمل على CPU." else
                            "Experimental: Whisper on Vulkan, translation on CPU. Falls back to CPU if needed."
                    }, color = V2.TextDim, fontSize = 12.sp)
                    if (vulkan && !overlap) Text(if (ar) "التداخل متاح مع Small أو Medium Q5 أو Turbo + مترجم 1.8B وعلى جهاز برام كافية." else
                        "Overlap requires Small, Medium Q5 or Turbo, the 1.8B translator, and sufficient device RAM.", color = V2.TextDim, fontSize = 11.sp)
                    if (!pairSupported) Text(OfflineIssue.FAST_PAIR.text(ar), color = V2.Gold, fontSize = 12.sp)
                    if (manifest) Text(if (ar) "اختار ملف فيديو أو صوت محفوظ واحد، مش قائمة HLS أو DASH." else "Choose a single saved video or audio file, not an HLS or DASH playlist.",
                        color = V2.Gold, fontSize = 12.sp)
                } else {
                    SectionTitle(if (ar) "المزودون" else "Providers")
                    PickerRow(if (ar) "التفريغ الصوتي" else "Transcription", stt, STTProvider.entries, { if (ar) it.titleAr else it.titleEn }) { stt = it }
                    PickerRow(if (ar) "مراجعة وتدقيق النصوص" else "Proofreading", refiner, RefinerProvider.entries, { if (ar) it.titleAr else it.titleEn }) { refiner = it }
                    PickerRow(if (ar) "الترجمة النصية" else "Translation", translator, TranslatorProvider.entries, { if (ar) it.titleAr else it.titleEn }) { translator = it }
                    Text(if (ar) "الوضع السحابي يرسل الصوت والنصوص للمزودين. المفاتيح في الإعدادات." else
                        "Cloud mode sends audio and text to providers. API keys are in Settings.", fontSize = 11.sp, color = V2.TextDim)
                }
                Spacer(Modifier.height(14.dp))
                val pack = OfflineModel.pack(speech, localTranslator, silenceGate)
                val ready = pack.all { models[it]?.installed == true }
                val compatible = remember(app, pack) { pack.all(app.offlineModels::compatible) }
                Button(onClick = {
                    if (offline) {
                        if (app.translations.startOffline(video.id, source, target, speech, localTranslator, fastSpeech, effective, silenceGate)) onDismiss()
                    } else {
                        app.translations.start(video.id, source, target, stt, refiner, translator)
                        onDismiss()
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !offline || (ready && work == null && !manifest && compatible && pairSupported)) {
                    Text(if (offline) { if (ar) "ابدأ على الجهاز" else "Start on device" } else { if (ar) "ابدأ بالسحابة" else "Start in cloud" })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OfflineModelPanel(
    app: Video2Application, ar: Boolean, speech: OfflineModel, onSelect: (OfflineModel) -> Unit,
    translator: OfflineModel, onTranslator: (OfflineModel) -> Unit, silenceGate: Boolean = false
) {
    val models by app.offlineModels.models.collectAsState()
    val issue by app.offlineModels.issue.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val downloading = work?.takeIf { it.kind == OfflineWorkKind.DOWNLOAD }
    var remove by remember { mutableStateOf<OfflineModel?>(null) }
    val pack = OfflineModel.pack(speech, translator, silenceGate)
    val compatible = remember(app, pack) { pack.all(app.offlineModels::compatible) }
    val ready = pack.all { models[it]?.installed == true }
    val remaining = pack.sumOf { (it.bytes - (models[it]?.downloaded ?: 0)).coerceAtLeast(0) }
    SectionTitle(if (ar) "الموديلات المحلية" else "Local models")
    PickerRow(if (ar) "موديل التفريغ" else "Speech model", speech, OfflineModel.speechModels, {
        val hint = when (it) {
            OfflineModel.MOONSHINE_EN -> if (ar) "إنجليزي فقط · تجريبي" else "English only · experimental"
            OfflineModel.WHISPER_SMALL_Q5 -> if (ar) "الأخف · للكلام الواضح" else "Lightest · clear speech"
            OfflineModel.WHISPER_MEDIUM_Q5 -> if (ar) "أخف · للصوت الواضح" else "Smaller · clear speech"
            OfflineModel.WHISPER_TURBO -> if (ar) "نسخة Turbo من Large-v3" else "Faster decoder than Large-v3"
            OfflineModel.WHISPER_LARGE -> if (ar) "للصوت الأصعب" else "Challenging speech"
            else -> if (ar) "تفريغ متوازن" else "Balanced speech"
        }
        "${it.label} · $hint"
    }, onSelect)
    PickerRow(if (ar) "موديل الترجمة" else "Translation model", translator, OfflineModel.translators, {
        val hint = when (it) {
            OfflineModel.FIREFOX_EN_AR -> if (ar) "إنجليزي إلى عربي مباشر" else "Direct English to Arabic"
            OfflineModel.FIREFOX_ES_AR, OfflineModel.FIREFOX_IT_AR -> if (ar) "عبر الإنجليزي · تجريبي" else "Via English · experimental"
            OfflineModel.HYMT_LITE -> if (ar) "موديل متعدد اللغات أخف" else "Lighter multilingual model"
            else -> if (ar) "دقة أعلى · أبطأ" else "Higher quality · slower"
        }
        "${it.label} · $hint"
    }, onTranslator)
    Text(if (translator.isFirefoxPivot) {
        if (ar) "Firefox يمرّ بالإنجليزي محليًا ويترجم كل سطر مستقل. حزمة الإنجليزي إلى عربي مشتركة. للحوار اللي بيعتمد على السياق اختار Hy‑MT2 من نفس التفريغ."
        else "Firefox pivots via English on device and translates cues independently. The English-to-Arabic pack is shared. Use Hy‑MT2 on the same transcript for dialogue context."
    } else if (translator.isFirefox) {
        if (ar) "Firefox بيترجم السطور في دفعات بس من غير سياق السطور المجاورة. راجع المصطلحات والمعنى في المقاطع الصعبة."
        else "Firefox batches separate cues without surrounding context. Review terminology and meaning in difficult passages."
    } else if (ar) "Hy‑MT2 بياخد السطور القريبة كسياق. ابدأ بـ1.8B للسرعة، واختار 7B للنص الصعب لو احتجته." else
        "Hy‑MT2 uses nearby lines as context. Start with 1.8B for speed; keep 7B for difficult text if needed.",
        fontSize = 11.sp, color = V2.TextDim)
    for (asset in pack) {
        val current = models[asset]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                Text(asset.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                val bytes = gb(asset.bytes)
                val state = when {
                    current?.installed == true -> if (ar) "مثبّت ومتحقق منه" else "Installed and verified"
                    (current?.downloaded ?: 0) > 0 -> "${gb(current!!.downloaded)} / $bytes"
                    else -> if (ar) "مش مثبّت" else "Not installed"
                }
                Text("$bytes · $state", fontSize = 11.sp, color = V2.TextDim)
            }
            if (current?.installed != true) TextButton(enabled = work == null && app.offlineModels.compatible(asset),
                onClick = { app.offlineModels.download(asset) }) {
                Text(if ((current?.downloaded ?: 0) > 0) { if (ar) "استئناف" else "Resume" }
                    else { if (ar) "تنزيل" else "Download" })
            }
            if ((current?.downloaded ?: 0) > 0) TextButton(enabled = work == null, onClick = { remove = asset }) {
                Text(if (ar) "إزالة" else "Remove")
            }
        }
    }
    if (!ready) Text(if (ar) "المتبقي للتنزيل: ${gb(remaining)}. يُفضّل واي فاي. الصوت والنصوص مابيطلعوش من جهازك." else
        "Remaining download: ${gb(remaining)}. Wi-Fi recommended. Audio and text stay on your device.",
        fontSize = 11.sp, color = V2.TextDim)
    else Text(if (ar) "جاهز من غير نت. المعالجة المحلية ممكن تاخد وقت وتسخّن الجهاز، خصوصًا مع الفيديو الطويل." else
        "Ready offline. Long videos can take time and warm up your phone.", fontSize = 11.sp, color = V2.TextDim)
    if (!compatible) {
        val blocked = if (speech.isMoonshine) app.offlineModels.moonshineIssue else null
        Text((blocked ?: OfflineIssue.NOT_SUPPORTED).text(ar), color = V2.Gold, fontSize = 12.sp)
    }
    if (downloading != null) {
        val ids = downloading.key.split('/')
        val selected = ids.mapNotNull { id -> OfflineModel.entries.firstOrNull { it.id == id } }.joinToString(" + ") { it.label }
        Text(if (ar) "جارٍ تجهيز $selected" else "Preparing $selected", fontSize = 11.sp, color = V2.TextDim)
        LinearProgressIndicator(progress = { downloading.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(enabled = !downloading.stopping, onClick = { app.offlineWork.cancel(OfflineWorkKind.DOWNLOAD, downloading.key) }) {
            Text(if (downloading.stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else { if (ar) "إيقاف التنزيل (قابل للاستئناف)" else "Stop download (resumable)" })
        }
    } else if (pack.count { models[it]?.installed != true } > 1) {
        val partial = pack.any { models[it]?.let { state -> state.downloaded > 0 && !state.installed } == true }
        Button(enabled = compatible && work == null, onClick = { app.offlineModels.downloadPack(speech, translator, silenceGate) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (partial) { if (ar) "استئناف تنزيل الموديلات" else "Resume model download" } else { if (ar) "تنزيل الموديلات" else "Download models" })
        }
    }
    issue?.let { Text(it.text(ar), fontSize = 12.sp, color = V2.Gold) }
    remove?.let { asset -> AlertDialog(onDismissRequest = { remove = null },
        title = { Text(if (ar) "إزالة ${asset.label}؟" else "Remove ${asset.label}?") },
        text = { Text(if (ar) "هنشيل نسخة التشغيل بس. ملفات المجلد والترجمات المحفوظة هتفضل موجودة." else
            "Only the installed copy is removed. Folder backups and saved subtitles are unchanged.") },
        confirmButton = { TextButton(onClick = { app.offlineModels.remove(asset); remove = null }) { Text(if (ar) "إزالة" else "Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(if (ar) "إلغاء" else "Cancel") } }) }
}

@Composable
private fun ModelFolderPanel(app: Video2Application, ar: Boolean) {
    val folder by app.offlineModels.folder.collectAsState()
    val count by app.offlineModels.transferCount.collectAsState()
    val models by app.offlineModels.models.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val transfer = work?.takeIf { it.kind in setOf(OfflineWorkKind.MODEL_BACKUP, OfflineWorkKind.MODEL_RESTORE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(app.offlineModels::chooseFolder)
    }
    SectionTitle(if (ar) "مجلد حفظ الموديلات" else "Model backup folder")
    Text(if (ar) "اختار مجلد محلي زي Documents/Video2 Models. بعد التنزيل بيتحفظ فيه نسخة من الأوزان تقدر تستردها من غير نت بعد إعادة التثبيت." else
        "Choose a local folder, e.g. Documents/Video2 Models. Downloads are backed up here for offline restore after reinstalling.",
        fontSize = 11.sp, color = V2.TextDim)
    folder?.let {
        val id = DocumentsContract.getTreeDocumentId(it)
        val volume = if (id.startsWith("primary:")) { if (ar) "التخزين الداخلي" else "Internal storage" } else id.substringBefore(':')
        Text("$volume / ${id.substringAfter(':')}", fontSize = 12.sp, color = V2.Mint,
            modifier = Modifier.padding(top = 4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = work == null, onClick = {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                folder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }) { Text(if (ar) "اختار المجلد" else "Choose folder") }
        if (folder != null) TextButton(enabled = work == null, onClick = { app.offlineModels.chooseFolder(null) }) {
            Text(if (ar) "فصل المجلد" else "Disconnect")
        }
    }
    if (folder != null) {
        Text(if (ar) "نسخة تشغيل ونسخة احتياطية: ده بياخد مساحة إضافية. المجلد للأوزان مش للفيديوهات أو المهام." else
            "An installed copy and a backup use extra storage. Only model weights are copied, not videos or jobs.",
            fontSize = 11.sp, color = V2.TextDim)
        TextButton(enabled = work == null && models.values.any { it.installed }, onClick = { app.offlineModels.backUpInstalled() }) {
            Text(if (ar) "احفظ الموديلات المثبتة في المجلد" else "Back up installed models")
        }
        Button(enabled = work == null, onClick = { app.offlineModels.restoreModels() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (ar) "استرداد الموديلات من المجلد" else "Restore models from folder")
        }
    }
    if (transfer != null) {
        val saving = transfer.kind == OfflineWorkKind.MODEL_BACKUP
        Text(if (saving) { if (ar) "جارٍ حفظ الأوزان…" else "Backing up weights…" }
            else { if (ar) "جارٍ الاسترداد والتحقق…" else "Restoring and verifying…" }, fontSize = 11.sp, color = V2.TextDim)
        LinearProgressIndicator(progress = { transfer.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(enabled = !transfer.stopping, onClick = { app.offlineWork.cancel(transfer.kind, transfer.key) }) {
            Text(if (ar) "إيقاف النقل" else "Stop transfer")
        }
    }
    if (work == null) count?.let {
        Text(if (it == 0) { if (ar) "مفيش ملفات جديدة اتثبتت." else "No new weights installed." }
            else { if (ar) "تم التحقق من $it ملف موديل." else "Verified $it model file(s)." }, fontSize = 11.sp, color = V2.Mint)
    }
}

@Composable
private fun RetiredModelCleanup(app: Video2Application, ar: Boolean, idle: Boolean) {
    var bytes by remember { mutableStateOf(app.offlineModels.retiredBytes()) }
    var confirm by remember { mutableStateOf(false) }
    if (bytes <= 0) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ar) "ملف ترجمة قديم غير مستخدم · ${gb(bytes)}" else "Unused legacy translation file · ${gb(bytes)}",
            modifier = Modifier.weight(1f), fontSize = 11.sp, color = V2.TextDim)
        TextButton(enabled = idle, onClick = { confirm = true }) { Text(if (ar) "إزالة" else "Remove") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (ar) "إزالة ملف الترجمة القديم؟" else "Remove the old translation file?") },
        text = { Text(if (ar) "ده هيفضّي مساحة من غير ما يحذف الفيديوهات أو الترجمات المحفوظة أو موديل Q4." else
            "This frees storage without removing videos, saved subtitles, or the Q4 model.") },
        confirmButton = { TextButton(enabled = idle, onClick = {
            app.offlineModels.removeRetired(); bytes = app.offlineModels.retiredBytes(); confirm = false
        }) { Text(if (ar) "إزالة" else "Remove") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(if (ar) "إلغاء" else "Cancel") } })
}

private fun gb(bytes: Long) = if (bytes < 300_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    else String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)

/** Subtitle review & edit — port of SubtitleReviewView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleReviewScreen(job: TranslationJob, ar: Boolean, onBack: () -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    var cues by remember { mutableStateOf(app.translations.cuesFor(job.id)) }
    var editing by remember { mutableStateOf<com.ahcrazy.video2.core.model.SubCue?>(null) }
    var mode by remember { mutableStateOf("bilingual") }
    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← " + if (ar) "رجوع" else "Back") }
            Column(Modifier.weight(1f)) {
                Text(job.videoTitle, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Text("${cues.size} " + if (ar) "سطر ترجمة" else "subtitle lines", fontSize = 11.sp, color = V2.TextDim)
            }
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            listOf("bilingual" to (if (ar) "ثنائي" else "Bilingual"), "translated" to (if (ar) "المترجم" else "Translated"), "original" to (if (ar) "الأصلي" else "Original")).forEach { (k, label) ->
                TextButton(onClick = { mode = k }) { Text(label, color = if (mode == k) V2.Accent else V2.TextDim) }
            }
        }
        if (cues.isEmpty()) EmptyState(Icons.Filled.ClosedCaption, if (ar) "مفيش نصوص محفوظة" else "No saved subtitles",
            if (ar) "ابدأ مهمة ترجمة من تبويب الترجمة." else "Start a job from the Translate tab.")
        LazyColumn {
            items(cues) { c ->
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { editing = c }) {
                    Column {
                        Text("#${c.index + 1} · ${SrtCodec.srtTime(c.startSec)} ➔ ${SrtCodec.srtTime(c.endSec)}", fontSize = 10.sp, color = V2.Gold)
                        if (mode != "translated") Text(c.original, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                        if (mode != "original") Text(c.translated.ifBlank { if (ar) "— لسه مترجمتش —" else "— not translated yet —" },
                            fontSize = 12.sp, color = if (c.translated.isBlank()) V2.Accent else V2.TextDim, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
    editing?.let { cue ->
        var orig by remember(cue.index) { mutableStateOf(cue.original) }
        var tran by remember(cue.index) { mutableStateOf(cue.translated) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { editing = null },
            title = { Text("${SrtCodec.srtTime(cue.startSec)} ➔ ${SrtCodec.srtTime(cue.endSec)}", fontSize = 12.sp) },
            text = { Column {
                Text(if (ar) "الأصلي / المُراجع" else "Original", fontSize = 11.sp, color = V2.TextDim)
                OutlinedTextField(orig, { orig = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(if (ar) "الترجمة" else "Translation", fontSize = 11.sp, color = V2.TextDim)
                OutlinedTextField(tran, { tran = it }, Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = {
                cues = cues.map { if (it.index == cue.index) it.copy(original = orig, translated = tran) else it }
                app.translations.saveCues(job.id, cues); editing = null
            }) { Text(if (ar) "حفظ" else "Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}
