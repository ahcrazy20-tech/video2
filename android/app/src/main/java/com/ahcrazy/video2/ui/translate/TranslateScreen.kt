package com.ahcrazy.video2.ui.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var showNew by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(preselected) { if (preselected != null) showNew = true }

    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ar) "الترجمة والدبلجة" else "Translate & Dub", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "تفريغ صوتي وترجمة للفيديوهات المحفوظة · ٣ ملفات SRT" else "Transcription and translation for saved videos · 3 SRT files", fontSize = 11.sp, color = V2.TextDim)
            }
            FloatingActionButton(onClick = { showNew = true }, containerColor = V2.Accent, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column {
                Text(if (ar) "ترجمة على الجهاز" else "On-device subtitles", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (ar) "نزّل الموديلات مرة واحدة. بعدها التفريغ والترجمة من غير إنترنت أو مفاتيح API." else
                    "Install the models once. Then transcribe and translate without internet or API keys.",
                    fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { showModels = true }) {
                    Text(if (localWork?.kind == OfflineWorkKind.DOWNLOAD) {
                        val percent = ((localWork?.progress ?: 0.0) * 100).toInt()
                        if (ar) "تنزيل الموديلات · $percent%" else "Downloading models · $percent%"
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
                val running = job.phase !in setOf(TranslationPhase.FAILED, TranslationPhase.PAUSED, TranslationPhase.CANCELLED, TranslationPhase.DONE)
                val stopping = offline && localWork?.key == job.id && localWork?.stopping == true
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(enabled = job.phase == TranslationPhase.DONE) { onReviewJob(job) }) {
                    Column {
                        Text(job.videoTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val engine = if (offline) {
                            val local = if (ar) "على الجهاز" else "On device"
                            "$local · ${OfflineModel.translator(job.offlineModel)?.label ?: "Hy-MT2"}"
                        } else STTProvider.of(job.sttProvider).let { if (ar) it.titleAr else it.titleEn }
                        Text("$engine → ${SubLang.of(job.targetLang).let { if (ar) it.nameAr else it.englishName }}",
                            fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else phaseText(job.phase, ar), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (job.phase == TranslationPhase.DONE) V2.Mint else if (job.phase == TranslationPhase.FAILED) V2.Accent else V2.Gold)
                            Spacer(Modifier.weight(1f))
                            Text("${(job.progress * 100).toInt()}%", fontSize = 11.sp, color = V2.TextDim)
                            if (job.cueCount > 0) Text(" · ${job.cueCount} " + if (ar) "سطر" else "lines", fontSize = 11.sp, color = V2.TextDim)
                        }
                        if (job.phase !in setOf(TranslationPhase.DONE)) LinearProgressIndicator(
                            progress = { job.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 7.dp), color = V2.Accent)
                        val issue = OfflineIssue.entries.firstOrNull { it.name == job.offlineIssue }
                        (issue?.text(ar) ?: job.message)?.let { Text(it, fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp)) }
                        Row {
                            if (job.phase == TranslationPhase.DONE) TextButton(onClick = { onReviewJob(job) }) { Text(if (ar) "مراجعة وتعديل" else "Review & edit") }
                            if (job.phase in setOf(TranslationPhase.FAILED, TranslationPhase.PAUSED, TranslationPhase.CANCELLED))
                                TextButton(enabled = !offline || localWork == null, onClick = { app.translations.resume(job.id) }) { Text(if (ar) "استئناف" else "Resume") }
                            if (running) TextButton(enabled = !stopping, onClick = { app.translations.cancel(job.id) }) { Text(if (ar) "إيقاف" else "Stop") }
                            Spacer(Modifier.weight(1f))
                            if (!running) TextButton(onClick = { app.translations.delete(job.id) }) { Text(if (ar) "حذف" else "Delete", color = V2.Accent) }
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewTranslationSheet(app, ar, videos, preselected, onDismiss = { showNew = false; onConsumePreselected() })
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }) {
        var model by remember { mutableStateOf(app.offlineModels.recommended) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (ar) "الموديلات المحلية" else "On-device models", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OfflineModelPanel(app, ar, model, { model = it })
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
private fun NewTranslationSheet(app: Video2Application, ar: Boolean, videos: List<SavedVideo>, preselected: SavedVideo?, onDismiss: () -> Unit) {
    var videoId by remember(preselected) { mutableStateOf(preselected?.id ?: videos.firstOrNull()?.id ?: "") }
    var source by remember { mutableStateOf(SubLang.AUTO) }
    var target by remember { mutableStateOf(SubLang.AR) }
    var mode by remember { mutableStateOf(TranslationMode.ON_DEVICE) }
    var model by remember { mutableStateOf(app.offlineModels.recommended) }
    var stt by remember { mutableStateOf(STTProvider.AUTO) }
    var refiner by remember { mutableStateOf(RefinerProvider.AUTO) }
    var translator by remember { mutableStateOf(TranslatorProvider.AUTO) }
    val models by app.offlineModels.models.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val offline = mode == TranslationMode.ON_DEVICE
    val video = videos.firstOrNull { it.id == videoId } ?: videos.firstOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (ar) "مهمة ترجمة جديدة" else "New translation job", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                if (offline) {
                    OfflineModelPanel(app, ar, model, { model = it })
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
                val ready = models[OfflineModel.WHISPER_LARGE]?.installed == true && models[model]?.installed == true
                Button(onClick = {
                    if (offline) {
                        if (app.translations.startOffline(video.id, source, target, model)) onDismiss()
                    } else {
                        app.translations.start(video.id, source, target, stt, refiner, translator)
                        onDismiss()
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !offline || (ready && work == null && !manifest && app.offlineModels.compatible(model))) {
                    Text(if (offline) { if (ar) "ابدأ على الجهاز" else "Start on device" } else { if (ar) "ابدأ بالسحابة" else "Start in cloud" })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OfflineModelPanel(app: Video2Application, ar: Boolean, model: OfflineModel, onSelect: (OfflineModel) -> Unit) {
    val models by app.offlineModels.models.collectAsState()
    val issue by app.offlineModels.issue.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val downloading = work?.takeIf { it.kind == OfflineWorkKind.DOWNLOAD }
    var remove by remember { mutableStateOf<OfflineModel?>(null) }
    val whisper = OfflineModel.WHISPER_LARGE
    val pack = listOf(whisper, model)
    val compatible = pack.all(app.offlineModels::compatible)
    val ready = pack.all { models[it]?.installed == true }
    val total = pack.sumOf { it.spec.bytes }
    SectionTitle(if (ar) "الموديلات المحلية" else "Local models")
    PickerRow(if (ar) "موديل الترجمة" else "Translation model", model, OfflineModel.translators, {
        it.label + if (it == OfflineModel.HYMT_Q6) { if (ar) " · يُفضّل ١٦ GB RAM" else " · 16 GB RAM recommended" } else ""
    }, onSelect)
    for (asset in pack) {
        val current = models[asset]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                Text(asset.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                val bytes = gb(asset.spec.bytes)
                val state = when {
                    current?.installed == true -> if (ar) "مثبّت ومتحقق منه" else "Installed and verified"
                    (current?.downloaded ?: 0) > 0 -> "${gb(current!!.downloaded)} / $bytes"
                    else -> if (ar) "مش مثبّت" else "Not installed"
                }
                Text("$bytes · $state", fontSize = 11.sp, color = V2.TextDim)
            }
            if ((current?.downloaded ?: 0) > 0) TextButton(enabled = work == null, onClick = { remove = asset }) {
                Text(if (ar) "إزالة" else "Remove")
            }
        }
    }
    if (!ready) Text(if (ar) "الحزمة ${gb(total)}. نزّلها على واي فاي من مصدر الموديلات. الصوت والنصوص مابيطلعوش من جهازك." else
        "${gb(total)} model pack. Wi-Fi recommended. Only model files are downloaded; audio and text stay on your device.",
        fontSize = 11.sp, color = V2.TextDim)
    else Text(if (ar) "جاهز من غير نت. المعالجة المحلية ممكن تاخد وقت وتسخّن الجهاز، خصوصًا مع الفيديو الطويل." else
        "Ready offline. Long videos can take time and warm up your phone.", fontSize = 11.sp, color = V2.TextDim)
    if (!compatible) Text(OfflineIssue.NOT_SUPPORTED.text(ar), color = V2.Gold, fontSize = 12.sp)
    if (downloading != null) {
        LinearProgressIndicator(progress = { downloading.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(enabled = !downloading.stopping, onClick = { app.offlineWork.cancel(OfflineWorkKind.DOWNLOAD, downloading.key) }) {
            Text(if (downloading.stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else { if (ar) "إيقاف التنزيل (قابل للاستئناف)" else "Stop download (resumable)" })
        }
    } else if (!ready) {
        val partial = pack.any { models[it]?.let { state -> state.downloaded > 0 && !state.installed } == true }
        Button(enabled = compatible && work == null, onClick = { app.offlineModels.downloadPack(model) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (partial) { if (ar) "استئناف تنزيل الموديلات" else "Resume model download" } else { if (ar) "تنزيل الموديلات" else "Download models" })
        }
    }
    issue?.let { Text(it.text(ar), fontSize = 12.sp, color = V2.Gold) }
    remove?.let { asset -> AlertDialog(onDismissRequest = { remove = null },
        title = { Text(if (ar) "إزالة ${asset.label}؟" else "Remove ${asset.label}?") },
        text = { Text(if (ar) "الترجمات المحفوظة هتفضل موجودة. لإعادة استخدام الموديل لازم تنزّله تاني." else
            "Saved subtitles will remain. You'll have to download the model again to use it.") },
        confirmButton = { TextButton(onClick = { app.offlineModels.remove(asset); remove = null }) { Text(if (ar) "إزالة" else "Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(if (ar) "إلغاء" else "Cancel") } }) }
}

private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)

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
