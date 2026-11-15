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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
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
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.RefinerProvider
import com.ahcrazy.video2.core.model.STTProvider
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.PickerRow
import com.ahcrazy.video2.ui.components.SectionTitle
import com.ahcrazy.video2.ui.theme.V2

@Composable
fun TranslateScreen(ar: Boolean, preselected: SavedVideo?, onConsumePreselected: () -> Unit,
                    onReviewJob: (TranslationJob) -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    val jobs by app.translations.jobs.collectAsState()
    val videos by app.library.videos.collectAsState()
    var showNew by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(preselected) { if (preselected != null) showNew = true }

    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ar) "الترجمة والدبلجة" else "Translate & Dub", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "تفريغ صوتي → تدقيق ذكاء اصطناعي → ترجمة سياقية → 3 ملفات SRT" else "Transcribe → AI proofread → contextual translate → 3 SRT files", fontSize = 11.sp, color = V2.TextDim)
            }
            FloatingActionButton(onClick = { showNew = true }, containerColor = V2.Accent, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column {
                Text(if (ar) "إزاي بتشتغل؟" else "How it works", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                listOf(
                    "1 · استخراج الصوت من الفيديو وتقطيعه أجزاء 15 دقيقة",
                    "2 · تفريغ الكلام بتوقيتات (Groq Whisper / Gemini / AssemblyAI…)",
                    "3 · مراجعة وتدقيق النصوص بالذكاء الاصطناعي وتصحيح أخطاء السمع",
                    "4 · ترجمة سياقية بالدفعات — ولو حصة أي مزود خلصت بنبدل للي بعده تلقائي",
                    "5 · كل خطوة بتتحفظ لحظيًا — القفل أو قطع النت بيكمل من نفس النقطة"
                ).forEach { Text(it, fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        if (jobs.isEmpty()) EmptyState(Icons.Filled.ClosedCaption,
            if (ar) "مفيش مهام ترجمة" else "No translation jobs",
            if (ar) "دوس + واختار فيديو من المكتبة نبدأ بيه" else "Tap + and pick a library video")
        LazyColumn {
            items(jobs) { job ->
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(enabled = job.phase == TranslationPhase.DONE) { onReviewJob(job) }) {
                    Column {
                        Text(job.videoTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("${STTProvider.of(job.sttProvider).let { if (ar) it.titleAr else it.titleEn }} → ${SubLang.of(job.targetLang).let { if (ar) it.nameAr else it.englishName }}",
                            fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(phaseText(job.phase, ar), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (job.phase == TranslationPhase.DONE) V2.Mint else if (job.phase == TranslationPhase.FAILED) V2.Accent else V2.Gold)
                            Spacer(Modifier.weight(1f))
                            Text("${(job.progress * 100).toInt()}%", fontSize = 11.sp, color = V2.TextDim)
                            if (job.cueCount > 0) Text(" · ${job.cueCount} " + if (ar) "سطر" else "lines", fontSize = 11.sp, color = V2.TextDim)
                        }
                        if (job.phase !in setOf(TranslationPhase.DONE)) LinearProgressIndicator(
                            progress = { job.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 7.dp), color = V2.Accent)
                        job.message?.let { Text(it, fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp), maxLines = 2) }
                        Row {
                            if (job.phase == TranslationPhase.DONE) TextButton(onClick = { onReviewJob(job) }) { Text(if (ar) "مراجعة وتعديل" else "Review & edit") }
                            if (job.phase == TranslationPhase.FAILED || job.phase == TranslationPhase.PAUSED || job.phase == TranslationPhase.CANCELLED)
                                TextButton(onClick = { app.translations.resume(job.id) }) { Text(if (ar) "استئناف" else "Resume") }
                            if (job.phase !in setOf(TranslationPhase.DONE, TranslationPhase.CANCELLED))
                                TextButton(onClick = { app.translations.cancel(job.id) }) { Text(if (ar) "إلغاء" else "Cancel") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { app.translations.delete(job.id) }) { Text(if (ar) "حذف" else "Delete", color = V2.Accent) }
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewTranslationSheet(ar, videos, preselected, onDismiss = { showNew = false; onConsumePreselected() },
        onStart = { vid, src, tgt, stt, ref, tr ->
            app.translations.start(vid, src, tgt, stt, ref, tr); showNew = false; onConsumePreselected()
        })
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
private fun NewTranslationSheet(ar: Boolean, videos: List<SavedVideo>, preselected: SavedVideo?,
                                onDismiss: () -> Unit, onStart: (String, SubLang, SubLang, STTProvider, RefinerProvider, TranslatorProvider) -> Unit) {
    var videoId by remember { mutableStateOf(preselected?.id ?: videos.firstOrNull()?.id ?: "") }
    var source by remember { mutableStateOf(SubLang.AUTO) }
    var target by remember { mutableStateOf(SubLang.AR) }
    var stt by remember { mutableStateOf(STTProvider.AUTO) }
    var refiner by remember { mutableStateOf(RefinerProvider.AUTO) }
    var translator by remember { mutableStateOf(TranslatorProvider.AUTO) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text(if (ar) "مهمة ترجمة جديدة" else "New translation job", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (videos.isEmpty()) Text(if (ar) "المكتبة فاضية — حمل أو استورد فيديو الأول." else "Library is empty — download or import a video first.", color = V2.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            else {
                PickerRow(if (ar) "الفيديو" else "Video", videos.firstOrNull { it.id == videoId } ?: videos.first(), videos, { it.title }) { videoId = it.id }
                SectionTitle(if (ar) "اللغات" else "Languages")
                PickerRow(if (ar) "لغة المصدر" else "Source", source, SubLang.entries, { if (ar) it.nameAr else it.englishName }) { source = it }
                PickerRow(if (ar) "لغة الهدف" else "Target", target, SubLang.entries.filter { it != SubLang.AUTO }, { if (ar) it.nameAr else it.englishName }) { target = it }
                SectionTitle(if (ar) "المزودون" else "Providers")
                PickerRow(if (ar) "التفريغ الصوتي" else "Transcription", stt, STTProvider.entries, { if (ar) it.titleAr else it.titleEn }) { stt = it }
                PickerRow(if (ar) "مراجعة وتدقيق النصوص" else "Proofreading", refiner, RefinerProvider.entries, { if (ar) it.titleAr else it.titleEn }) { refiner = it }
                PickerRow(if (ar) "الترجمة النصية" else "Translation", translator, TranslatorProvider.entries, { if (ar) it.titleAr else it.titleEn }) { translator = it }
                Text(if (ar) "المفاتيح من الإعدادات — MyMemory بيشتغل من غير مفتاح كملاذ أخير." else "Keys live in Settings — MyMemory works keyless as a last resort.", fontSize = 11.sp, color = V2.TextDim)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onStart(videoId, source, target, stt, refiner, translator) }, Modifier.fillMaxWidth()) {
                    Text(if (ar) "ابدأ الترجمة" else "Start translation")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

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
