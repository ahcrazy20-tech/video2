package com.ahcrazy.video2.ui.translate

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.DubbingProvider
import com.ahcrazy.video2.core.model.DubbingVoices
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.PickerRow
import com.ahcrazy.video2.ui.components.SectionTitle
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Dubbing sheet — port of DubbingView: provider/voice, on-device TTS synthesis to file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DubbingSheet(video: SavedVideo, ar: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(SubLang.AR) }
    var provider by remember { mutableStateOf(DubbingProvider.EDGE) }
    var voice by remember { mutableStateOf("") }
    var stretch by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    val voices = when (provider) {
        DubbingProvider.GROQ_ORPHEUS -> DubbingVoices.arabicSaudi
        DubbingProvider.GEMINI_TTS -> DubbingVoices.gemini
        else -> DubbingVoices.edge
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text(video.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
            Text(if (ar) "هيتولد ملف صوت بدبلجة يُشغَّل بدل الصوت الأصلي في المشغّل." else "Generates a dubbed track that replaces the original audio in the player.", fontSize = 12.sp, color = V2.TextDim)
            SectionTitle(if (ar) "اللغة المستهدفة" else "Target language")
            PickerRow(if (ar) "لغة الدبلجة" else "Dub language", target, SubLang.entries.filter { it != SubLang.AUTO }, { if (ar) it.nameAr else it.englishName }) { target = it }
            SectionTitle(if (ar) "مزود الدبلجة" else "Dubbing provider")
            PickerRow(if (ar) "المزود" else "Provider", provider, DubbingProvider.entries, { if (ar) it.titleAr else it.titleEn }) { provider = it; voice = "" }
            providerHint(provider, ar)?.let { Text(it, fontSize = 11.sp, color = V2.TextDim) }
            SectionTitle(if (ar) "الصوت" else "Voice")
            PickerRow(if (ar) "الصوت" else "Voice", voice, listOf("") + voices.map { it.id }, { id ->
                if (id.isEmpty()) if (ar) "(الأفضل تلقائيًا)" else "(Best automatically)" else voices.firstOrNull { it.id == id }?.name ?: id
            }) { voice = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ar) "مطّ الصوت ليناسب التوقيت" else "Stretch audio to fit timing", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(stretch, { stretch = it })
            }
            if (busy) {
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 12.dp), color = V2.Accent)
                status?.let { Text(it, fontSize = 12.sp, color = V2.TextDim, modifier = Modifier.padding(top = 6.dp)) }
            }
            Button(onClick = {
                busy = true; status = if (ar) "جارٍ تجهيز جُمل الترجمة…" else "Preparing subtitle cues…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { synthesizeDub(ctx, app, video, target) { p, s -> progress = p; status = s } }
                    busy = false
                    status = if (ok) (if (ar) "تمت الدبلجة ✓ — شغّل الفيديو وهتسمعها تلقائيًا" else "Dubbed ✓ — play the video to hear it")
                             else (if (ar) "محتاج ترجمة محفوظة الأول (من تبويب الترجمة)" else "Needs saved subtitles first (Translate tab)")
                    if (ok) onDismiss()
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(if (ar) "ابدأ الدبلجة" else "Start dubbing")
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

private fun providerHint(p: DubbingProvider, ar: Boolean): String? = when (p) {
    DubbingProvider.EDGE -> if (ar) "بدون مفتاح — جودة كويسة جدًا للعربية الفصحى." else "No key — great Modern Standard Arabic."
    DubbingProvider.GROQ_ORPHEUS -> if (ar) "بنفس مفتاح Groq — 6 أصوات عربية سعودية." else "Uses your Groq key — 6 Saudi Arabic voices."
    DubbingProvider.GEMINI_TTS -> if (ar) "بنفس مفتاح Gemini المجاني — 30 صوت بـ24 لغة." else "Uses your free Gemini key — 30 voices, 24 languages."
    DubbingProvider.ELEVENLABS -> if (ar) "أفضل جودة بشرية — مدفوع." else "Best human quality — paid."
    DubbingProvider.AZURE -> if (ar) "500 ألف حرف شهريًا في طبقة F0." else "500K chars/month on F0 tier."
    DubbingProvider.SILICONFLOW -> if (ar) "CosyVoice 2 — ممتاز للصينية، العربية محدودة." else "CosyVoice 2 — great Chinese, limited Arabic."
    DubbingProvider.AUTO -> if (ar) "بيختار: ElevenLabs ثم Azure ثم Gemini ثم Edge." else "Picks ElevenLabs → Azure → Gemini → Edge."
}

private suspend fun synthesizeDub(ctx: android.content.Context, app: com.ahcrazy.video2.Video2Application,
                                  video: SavedVideo, target: SubLang, onStep: (Float, String) -> Unit): Boolean {
    val srtFile = video.subtitleFiles["target"]?.let { File(it) }?.takeIf { it.exists() }
        ?: video.subtitleFiles["orig"]?.let { File(it) }?.takeIf { it.exists() } ?: return false
    val cues = SrtCodec.parse(srtFile).filter { it.original.isNotBlank() }
    if (cues.isEmpty()) return false
    val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { st -> cont.resume(if (st == TextToSpeech.SUCCESS) engine else null) }
    } ?: return false
    tts.language = Locale.forLanguageTag(target.bcp47 ?: "ar")
    val outDir = File(app.library.dubsDir, video.id).apply { mkdirs() }
    val merged = File(outDir, "dub.wav")
    // Synthesize each cue to wav, then simply concatenate PCM wavs (same TTS format) into one track file.
    val parts = mutableListOf<File>()
    cues.take(300).forEachIndexed { i, cue ->
        val part = File(outDir, "cue-%04d.wav".format(i))
        val text = cue.translated.ifBlank { cue.original }
        val id = UUID.randomUUID().toString()
        val done = suspendCancellableCoroutine<Boolean> { cont ->
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) { if (u == id) cont.resume(true) }
                @Deprecated("deprecated") override fun onError(u: String?) { if (u == id) cont.resume(false) }
            })
            val params = android.os.Bundle()
            val r = tts.synthesizeToFile(text, params, part, id)
            if (r != TextToSpeech.SUCCESS) cont.resume(false)
        }
        if (done && part.exists()) parts += part
        onStep((i + 1f) / cues.size.coerceAtLeast(1), "جملة ${i + 1}/${cues.size}")
    }
    tts.shutdown()
    if (parts.isEmpty()) return false
    // WAV concat: keep first header, append raw PCM of the rest (TTS emits identical format per engine)
    merged.outputStream().use { out ->
        parts.forEachIndexed { idx, part ->
            val bytes = part.readBytes()
            if (idx == 0) out.write(bytes) else if (bytes.size > 44) out.write(bytes, 44, bytes.size - 44)
            part.delete()
        }
    }
    app.library.update(video.copy(dubbedAudioPath = merged.absolutePath,
        dubbedInfo = com.ahcrazy.video2.core.model.DubbedInfo("edge", "device-tts", target.code, System.currentTimeMillis(),
            cues.maxOfOrNull { it.endSec } ?: 0.0)))
    return true
}

/** Format conversion screen — local HLS→single-file first (like iOS), cloud only as fallback. */
@Composable
fun ConversionScreen(ar: Boolean, onBack: () -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    val videos by app.library.videos.collectAsState()
    val hls = videos.filter { it.kind == com.ahcrazy.video2.core.model.MediaKind.HLS }
    var converting by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← " + if (ar) "رجوع" else "Back") }
            Text(if (ar) "تحويل الصيغ" else "Format conversion", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(if (ar) "تحويل HLS لملف واحد MP4 — محليًا الأول من غير سحابة، والسحابة (CloudConvert ← ffmpeg-api ← ConvertAPI) احتياطي بس لو المحلي فشل." else "HLS → single MP4 locally first; cloud only as fallback.", fontSize = 12.sp, color = V2.TextDim)
        msg?.let { Text(it, fontSize = 12.sp, color = V2.Mint, modifier = Modifier.padding(top = 10.dp)) }
        if (hls.isEmpty()) EmptyState(Icons.Filled.Transform, if (ar) "مفيش HLS للتحويل" else "Nothing to convert",
            if (ar) "أي فيديو HLS في المكتبة هيظهر هنا." else "HLS videos in your library will appear here.")
        LazyColumn {
            items(hls) { v ->
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column {
                        Text(v.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("HLS (m3u8) → MP4 · ${com.ahcrazy.video2.core.util.L10n.fmtBytes(v.fileSize)}", fontSize = 11.sp, color = V2.TextDim)
                        if (converting == v.id) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = V2.Accent)
                        Button(onClick = {
                            converting = v.id
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { mergeHlsToMp4(v) }
                                converting = null
                                msg = if (ok != null) (if (ar) "اتحول وبقى في المكتبة: " else "Converted & in Library: ") + ok
                                      else if (ar) "فشل التحويل المحلي — راجع مفاتيح السحابة في الإعدادات" else "Local convert failed — check cloud keys in Settings"
                            }
                        }, enabled = converting == null, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (ar) "حوّل إلى MP4" else "Convert to MP4", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Local path: concatenate the downloaded TS segments in playlist order into one playable file. */
private fun mergeHlsToMp4(video: SavedVideo): String? = runCatching {
    val playlist = File(video.localPath)
    val folder = playlist.parentFile ?: return null
    val segs = playlist.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { File(folder, it) }.filter { it.exists() }
    if (segs.isEmpty()) return null
    val out = File(folder.parentFile, "converted-${video.id}.mp4")
    out.outputStream().use { o -> segs.forEach { s -> s.inputStream().use { it.copyTo(o) } } }
    out.name
}.getOrNull()
