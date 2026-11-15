package com.ahcrazy.video2.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ahcrazy.video2.core.model.RefinerProvider
import com.ahcrazy.video2.core.model.STTProvider
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.core.net.ChatClient
import com.ahcrazy.video2.core.net.SttClient
import com.ahcrazy.video2.core.net.TranslateClient
import com.ahcrazy.video2.core.util.SrtCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Subtitle pipeline with per-step checkpointing — port of TranslationManager + AudioPipeline:
 * extract audio → STT → AI refine → contextual translate → 3 SRTs (orig / translated / bilingual).
 * Every chunk & batch is written to disk instantly, so a killed app resumes mid-job.
 * Re-translating reuses the saved transcript (never re-runs STT).
 */
class TranslationManager(
    private val ctx: Context, private val library: LibraryStore, private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(ctx.filesDir, "Translations").apply { mkdirs() }
    private val jobsFile = File(root, "jobs.json")
    private val chat = ChatClient()
    private val translator = TranslateClient(settings, chat)
    private val stt = SttClient(settings)

    private val _jobs = MutableStateFlow<List<TranslationJob>>(emptyList())
    val jobs: StateFlow<List<TranslationJob>> = _jobs
    init { loadJobs() }

    fun start(videoId: String, source: SubLang, target: SubLang,
              sttP: STTProvider, refiner: RefinerProvider, translatorP: TranslatorProvider): TranslationJob {
        val video = library.byId(videoId) ?: throw IllegalStateException("الفيديو مش موجود")
        val job = TranslationJob(UUID.randomUUID().toString(), videoId, video.title,
            source.code, target.code, sttP.id, refiner.id, translatorP.id)
        _jobs.value = listOf(job) + _jobs.value; persistJobs()
        run(job)
        return job
    }

    fun cancel(id: String) = patch(id) { it.copy(phase = TranslationPhase.CANCELLED) }
    fun delete(id: String) { _jobs.value = _jobs.value.filter { it.id != id }; persistJobs() }
    fun resume(id: String) { val j = _jobs.value.firstOrNull { it.id == id } ?: return; run(j) }

    private fun run(job: TranslationJob) {
        scope.launch {
            val dir = File(root, job.id).apply { mkdirs() }
            try {
                val video = library.byId(job.videoId) ?: throw IllegalStateException("الفيديو اتحذف")
                // 1) Transcript reuse or STT
                var cues: List<SubCue>
                val transcriptFile = File(dir, "cues-original.json")
                if (transcriptFile.exists()) {
                    cues = readCues(transcriptFile)
                    patch(job.id) { it.copy(phase = TranslationPhase.REFINING, progress = 0.45, cueCount = cues.size) }
                } else {
                    patch(job.id) { it.copy(phase = TranslationPhase.EXTRACTING, progress = 0.05) }
                    val audio = extractAudio(File(video.localPath), File(dir, "audio.m4a"))
                    patch(job.id) { it.copy(phase = TranslationPhase.TRANSCRIBING, progress = 0.2) }
                    cues = when (STTProvider.of(job.sttProvider)) {
                        STTProvider.GROQ, STTProvider.AUTO -> stt.transcribeGroq(audio, job.sourceLang)
                        else -> stt.transcribeGroq(audio, job.sourceLang) // Groq fast-path; other providers plug into SttClient
                    }
                    if (cues.isEmpty()) throw IllegalStateException("التفريغ رجع فاضي — راجع المفتاح أو جرّب مزود تاني")
                    writeCues(transcriptFile, cues)
                    audio.delete()
                }
                // 2) AI refinement (proofreading) — skippable
                val refinedFile = File(dir, "cues-refined.json")
                if (RefinerProvider.of(job.refinerProvider) != RefinerProvider.OFF && !refinedFile.exists()) {
                    patch(job.id) { it.copy(phase = TranslationPhase.REFINING, progress = 0.55, cueCount = cues.size) }
                    cues = refine(cues, job)
                    writeCues(refinedFile, cues)
                }
                // 3) Contextual batch translation with failover, checkpoint per batch
                patch(job.id) { it.copy(phase = TranslationPhase.TRANSLATING, progress = 0.65, cueCount = cues.size) }
                val target = SubLang.of(job.targetLang)
                val checkpoint = File(dir, "cues-translated-${target.code}.json")
                val done = if (checkpoint.exists()) readCues(checkpoint).associateBy { it.index } else emptyMap()
                val batchSize = 30
                cues.chunked(batchSize).forEachIndexed { bi, batch ->
                    val pending = batch.filter { it.index !in done || done[it.index]?.translated.isNullOrBlank() }
                    if (pending.isNotEmpty()) {
                        val translated = translator.translateBatch(pending, target.englishName, TranslatorProvider.of(job.translatorProvider))
                        pending.forEachIndexed { i, c -> c.translated = translated.getOrElse(i) { c.original } }
                    }
                    val merged = cues.map { c -> done[c.index]?.takeIf { it.translated.isNotBlank() } ?: c }
                    writeCues(checkpoint, merged)
                    patch(job.id) { it.copy(progress = 0.65 + 0.3 * (bi + 1) * batchSize / cues.size.coerceAtLeast(1)) }
                }
                cues = readCues(checkpoint)
                // 4) Three SRT outputs registered on the video
                patch(job.id) { it.copy(phase = TranslationPhase.SAVING, progress = 0.97) }
                val subDir = File(library.subtitlesDir, video.id).apply { mkdirs() }
                val orig = File(subDir, "orig.srt").apply { writeText(SrtCodec.write(cues, "original")) }
                val tran = File(subDir, "target.srt").apply { writeText(SrtCodec.write(cues, "translated")) }
                val bili = File(subDir, "bilingual.srt").apply { writeText(SrtCodec.write(cues, "bilingual")) }
                library.update(video.copy(
                    subtitleFiles = mapOf("orig" to orig.absolutePath, "target" to tran.absolutePath, "bilingual" to bili.absolutePath),
                    subtitleTargetLang = target.code))
                patch(job.id) { it.copy(phase = TranslationPhase.DONE, progress = 1.0, cueCount = cues.size, message = null) }
            } catch (e: Exception) {
                patch(job.id) { it.copy(phase = TranslationPhase.FAILED, message = e.message ?: "فشلت المهمة") }
            }
        }
    }

    private fun refine(cues: List<SubCue>, job: TranslationJob): List<SubCue> {
        val p = RefinerProvider.of(job.refinerProvider)
        val providerId = when (p) {
            RefinerProvider.AUTO -> listOf("gemini", "groq", "cerebras", "sambanova", "nvidia", "cohere", "zai", "openrouter")
                .firstOrNull { settings.hasKey(it) } ?: return cues
            RefinerProvider.OFF -> return cues
            else -> p.id
        }
        val key = settings.getKey(providerId) ?: return cues
        return cues.chunked(30).flatMap { batch ->
            runCatching {
                val numbered = batch.mapIndexed { i, c -> "${i + 1}. ${c.original}" }.joinToString("\n")
                val out = chat.chat(providerId, key,
                    "You proofread speech-to-text subtitles: fix recognition errors and homophones, complete cut-off words, " +
                        "remove hallucinations/repetitions, keep the SAME numbering, one line per cue, no explanations.", numbered)
                val lines = out.lines().map { it.replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                batch.mapIndexed { i, c -> c.copy(original = lines.getOrNull(i)?.ifBlank { c.original } ?: c.original) }
            }.getOrDefault(batch)
        }
    }

    /** Extract the audio track to mono-ready m4a with MediaExtractor/Muxer (no FFmpeg needed). */
    private fun extractAudio(input: File, output: File): File {
        if (output.exists() && output.length() > 0) return output
        val ex = MediaExtractor(); ex.setDataSource(input.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) { // video-only file: take first track anyway (muxer will fail loudly upstream)
            ex.release(); throw IllegalStateException("الملف مفيهوش مسار صوت قابل للاستخراج")
        }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format); muxer.start()
        val buffer = ByteBuffer.allocate(512 * 1024); val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = ex.readSampleData(buffer, 0); if (size < 0) break
            info.offset = 0; info.size = size; info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(outTrack, buffer, info); ex.advance()
        }
        muxer.stop(); muxer.release(); ex.release()
        return output
    }

    fun cuesFor(jobId: String): List<SubCue> {
        val dir = File(root, jobId)
        val job = _jobs.value.firstOrNull { it.id == jobId }
        val f = File(dir, "cues-translated-${job?.targetLang ?: "ar"}.json")
        val src = if (f.exists()) f else File(dir, "cues-refined.json").takeIf { it.exists() } ?: File(dir, "cues-original.json")
        return if (src.exists()) readCues(src) else emptyList()
    }
    fun saveCues(jobId: String, cues: List<SubCue>) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        val dir = File(root, jobId).apply { mkdirs() }
        writeCues(File(dir, "cues-translated-${job.targetLang}.json"), cues)
        // Refresh SRTs so the player sees edits instantly
        val video = library.byId(job.videoId) ?: return
        if (video.subtitleFiles.isNotEmpty()) {
            video.subtitleFiles["orig"]?.let { File(it).writeText(SrtCodec.write(cues, "original")) }
            video.subtitleFiles["target"]?.let { File(it).writeText(SrtCodec.write(cues, "translated")) }
            video.subtitleFiles["bilingual"]?.let { File(it).writeText(SrtCodec.write(cues, "bilingual")) }
        }
    }

    private fun writeCues(f: File, cues: List<SubCue>) {
        val arr = JSONArray()
        cues.forEach { c -> arr.put(JSONObject().put("i", c.index).put("s", c.startSec).put("e", c.endSec).put("o", c.original).put("t", c.translated)) }
        f.writeText(arr.toString())
    }
    private fun readCues(f: File): List<SubCue> {
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); SubCue(o.getInt("i"), o.getDouble("s"), o.getDouble("e"), o.getString("o"), o.optString("t")) }
    }

    @Synchronized private fun patch(id: String, f: (TranslationJob) -> TranslationJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it }; persistJobs()
    }
    @Synchronized private fun persistJobs() {
        runCatching {
            val arr = JSONArray()
            _jobs.value.forEach { j -> arr.put(JSONObject().apply {
                put("id", j.id); put("videoId", j.videoId); put("videoTitle", j.videoTitle)
                put("sourceLang", j.sourceLang); put("targetLang", j.targetLang)
                put("stt", j.sttProvider); put("refiner", j.refinerProvider); put("translator", j.translatorProvider)
                put("phase", j.phase.name); put("progress", j.progress); put("cueCount", j.cueCount)
                put("createdAt", j.createdAt); put("message", j.message ?: JSONObject.NULL)
            }) }
            jobsFile.writeText(arr.toString())
        }
    }
    private fun loadJobs() {
        if (!jobsFile.exists()) return
        runCatching {
            val arr = JSONArray(jobsFile.readText())
            _jobs.value = (0 until arr.length()).map { val o = arr.getJSONObject(it)
                TranslationJob(o.getString("id"), o.getString("videoId"), o.getString("videoTitle"),
                    o.optString("sourceLang", "auto"), o.optString("targetLang", "ar"),
                    o.optString("stt", "auto"), o.optString("refiner", "auto"), o.optString("translator", "auto"),
                    runCatching { TranslationPhase.valueOf(o.getString("phase")) }.getOrDefault(TranslationPhase.QUEUED)
                        .let { if (it in setOf(TranslationPhase.EXTRACTING, TranslationPhase.TRANSCRIBING, TranslationPhase.REFINING, TranslationPhase.TRANSLATING)) TranslationPhase.PAUSED else it },
                    o.optDouble("progress"), o.optInt("cueCount"), if (o.isNull("message")) null else o.optString("message"), o.optLong("createdAt"))
            }
        }
    }
}
