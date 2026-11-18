package com.ahcrazy.video2.ui.translate

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ahcrazy.video2.data.offline.RETIRED_TRANSLATOR_ID
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
                            val model = when (OfflineModel.translatorForJob(job.offlineModel)) {
                                OfflineModel.HYMT_LITE -> "1.8B Q4"
                                OfflineModel.HYMT_Q4 -> "7B Q4"
                                else -> "Q6".takeIf { retired }
                            }
                            listOfNotNull(local, speech, (if (ar) "سريع" else "Fast").takeIf { job.offlineFastSpeech }, model).joinToString(" · ")
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
                        val issue = if (retired && job.phase != TranslationPhase.DONE) OfflineIssue.RETIRED_TRANSLATOR
                            else OfflineIssue.entries.firstOrNull { it.name == job.offlineIssue }
                        (issue?.text(ar) ?: job.message)?.let { Text(it, fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp)) }
                        if (!running && app.translations.canRetranslate(job)) {
                            val hasLight = installed[OfflineModel.HYMT_LITE]?.installed == true
                            TextButton(enabled = localWork == null, onClick = {
                                if (hasLight) app.translations.retranslateLight(job.id) else showModels = true
                            }) {
                                Text(if (hasLight) {
                                    if (ar) "ترجم بـ1.8B من التفريغ المحفوظ" else "Translate saved transcript with 1.8B"
                                } else {
                                    if (ar) "نزّل 1.8B لإعادة الترجمة فقط" else "Get 1.8B to retranslate without transcribing"
                                })
                            }
                        }
                        Row {
                            if (job.phase == TranslationPhase.DONE) TextButton(onClick = { onReviewJob(job) }) { Text(if (ar) "مراجعة وتعديل" else "Review & edit") }
                            if (!retired && job.phase in setOf(TranslationPhase.FAILED, TranslationPhase.PAUSED, TranslationPhase.CANCELLED))
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
        var speech by remember { mutableStateOf(app.offlineModels.defaultSpeech) }
        var model by remember { mutableStateOf(OfflineModel.HYMT_LITE) }
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
private fun NewTranslationSheet(app: Video2Application, ar: Boolean, videos: List<SavedVideo>, preselected: SavedVideo?, onDismiss: () -> Unit) {
    var videoId by remember(preselected) { mutableStateOf(preselected?.id ?: videos.firstOrNull()?.id ?: "") }
    var source by remember { mutableStateOf(SubLang.AUTO) }
    var target by remember { mutableStateOf(SubLang.AR) }
    var mode by remember { mutableStateOf(TranslationMode.ON_DEVICE) }
    var speech by remember { mutableStateOf(app.offlineModels.defaultSpeech) }
    var fastSpeech by remember { mutableStateOf(false) }
    var localTranslator by remember { mutableStateOf(app.offlineModels.defaultTranslator) }
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
                    OfflineModelPanel(app, ar, speech, { speech = it }, localTranslator, { localTranslator = it })
                    if (speech.supportsFastSpeech) {
                        PickerRow(if (ar) "سرعة التفريغ" else "Transcription speed", fastSpeech, listOf(false, true), {
                            if (it) { if (ar) "سريع · للكلام الواضح" else "Fast · clear speech" }
                            else { if (ar) "دقة أعلى" else "Higher accuracy" }
                        }) { fastSpeech = it }
                    }
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
                val pack = OfflineModel.pack(speech, localTranslator)
                val ready = pack.all { models[it]?.installed == true }
                val compatible = remember(app, speech, localTranslator) { pack.all(app.offlineModels::compatible) }
                Button(onClick = {
                    if (offline) {
                        if (app.translations.startOffline(video.id, source, target, speech, localTranslator, fastSpeech)) onDismiss()
                    } else {
                        app.translations.start(video.id, source, target, stt, refiner, translator)
                        onDismiss()
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !offline || (ready && work == null && !manifest && compatible)) {
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
    translator: OfflineModel, onTranslator: (OfflineModel) -> Unit
) {
    val models by app.offlineModels.models.collectAsState()
    val issue by app.offlineModels.issue.collectAsState()
    val work by app.offlineWork.status.collectAsState()
    val downloading = work?.takeIf { it.kind == OfflineWorkKind.DOWNLOAD }
    var remove by remember { mutableStateOf<OfflineModel?>(null) }
    val pack = OfflineModel.pack(speech, translator)
    val compatible = remember(app, speech, translator) { pack.all(app.offlineModels::compatible) }
    val ready = pack.all { models[it]?.installed == true }
    val remaining = pack.sumOf { (it.spec.bytes - (models[it]?.downloaded ?: 0)).coerceAtLeast(0) }
    SectionTitle(if (ar) "الموديلات المحلية" else "Local models")
    PickerRow(if (ar) "موديل التفريغ" else "Speech model", speech, OfflineModel.speechModels, {
        val hint = when (it) {
            OfflineModel.WHISPER_MEDIUM_Q5 -> if (ar) "أخف · للصوت الواضح" else "Smaller · clear speech"
            OfflineModel.WHISPER_LARGE -> if (ar) "للصوت الأصعب" else "Challenging speech"
            else -> if (ar) "تفريغ متوازن" else "Balanced speech"
        }
        "${it.label} · $hint"
    }, onSelect)
    PickerRow(if (ar) "موديل الترجمة" else "Translation model", translator, OfflineModel.translators, {
        val hint = if (it == OfflineModel.HYMT_LITE) { if (ar) "الأخف" else "Lighter" }
            else { if (ar) "دقة أعلى · أبطأ" else "Higher quality · slower" }
        "${it.label} · $hint"
    }, onTranslator)
    Text(if (ar) "الاتنين بياخدوا السطور القريبة كسياق. ابدأ بـ1.8B للسرعة، واختار 7B للنص الصعب لو احتجته." else
        "Both use nearby lines as context. Start with 1.8B for speed; keep 7B for difficult text if needed.",
        fontSize = 11.sp, color = V2.TextDim)
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
    if (!ready) Text(if (ar) "المتبقي للتنزيل: ${gb(remaining)}. يُفضّل واي فاي. الصوت والنصوص مابيطلعوش من جهازك." else
        "Remaining download: ${gb(remaining)}. Wi-Fi recommended. Audio and text stay on your device.",
        fontSize = 11.sp, color = V2.TextDim)
    else Text(if (ar) "جاهز من غير نت. المعالجة المحلية ممكن تاخد وقت وتسخّن الجهاز، خصوصًا مع الفيديو الطويل." else
        "Ready offline. Long videos can take time and warm up your phone.", fontSize = 11.sp, color = V2.TextDim)
    if (!compatible) Text(OfflineIssue.NOT_SUPPORTED.text(ar), color = V2.Gold, fontSize = 12.sp)
    if (downloading != null) {
        val selected = listOfNotNull(OfflineModel.speechForJob(downloading.key.substringBefore('/')),
            OfflineModel.translatorForJob(downloading.key.substringAfter('/'))).joinToString(" + ") { it.label }
        Text(if (ar) "جارٍ تجهيز $selected" else "Preparing $selected", fontSize = 11.sp, color = V2.TextDim)
        LinearProgressIndicator(progress = { downloading.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(enabled = !downloading.stopping, onClick = { app.offlineWork.cancel(OfflineWorkKind.DOWNLOAD, downloading.key) }) {
            Text(if (downloading.stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else { if (ar) "إيقاف التنزيل (قابل للاستئناف)" else "Stop download (resumable)" })
        }
    } else if (!ready) {
        val partial = pack.any { models[it]?.let { state -> state.downloaded > 0 && !state.installed } == true }
        Button(enabled = compatible && work == null, onClick = { app.offlineModels.downloadPack(speech, translator) }, modifier = Modifier.fillMaxWidth()) {
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
        Text(DocumentsContract.getTreeDocumentId(it).substringAfter(':'), fontSize = 12.sp, color = V2.Mint,
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
