package com.ahcrazy.video2.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.RefinerProvider
import com.ahcrazy.video2.core.model.STTProvider
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.core.model.TranslationMode
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.core.net.ChatClient
import com.ahcrazy.video2.core.net.SttClient
import com.ahcrazy.video2.core.net.TranslateClient
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.offline.CueJson
import com.ahcrazy.video2.data.offline.OfflineFailure
import com.ahcrazy.video2.data.offline.OfflineIssue
import com.ahcrazy.video2.data.offline.OfflineModel
import com.ahcrazy.video2.data.offline.OfflineModels
import com.ahcrazy.video2.data.offline.OfflinePipeline
import com.ahcrazy.video2.data.offline.OfflineWork
import com.ahcrazy.video2.data.offline.OfflineWorkKind
import com.ahcrazy.video2.data.offline.atomicText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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

/** Library subtitle jobs, with a persisted, no-fallback split between local and cloud inference. */
class TranslationManager internal constructor(
    ctx: Context, private val library: LibraryStore, private val settings: AppSettings,
    private val offlineModels: OfflineModels, private val offlineWork: OfflineWork
) {
    private companion object {
        val RESUMABLE = setOf(TranslationPhase.PAUSED, TranslationPhase.FAILED, TranslationPhase.CANCELLED)
        val ACTIVE = TranslationPhase.entries.toSet() - RESUMABLE - TranslationPhase.DONE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(ctx.filesDir, "Translations").apply { mkdirs() }
    private val jobsFile = File(root, "jobs.json")
    private val chat by lazy { ChatClient() }
    private val translator by lazy { TranslateClient(settings, chat) }
    private val stt by lazy { SttClient(settings) }
    private val offline = OfflinePipeline(offlineModels::path)
    private val cloudWorkers = mutableMapOf<String, Job>()

    private val _jobs = MutableStateFlow<List<TranslationJob>>(emptyList())
    val jobs: StateFlow<List<TranslationJob>> = _jobs
    init { loadJobs() }

    @Synchronized fun start(videoId: String, source: SubLang, target: SubLang,
              sttP: STTProvider, refiner: RefinerProvider, translatorP: TranslatorProvider): TranslationJob {
        val video = library.byId(videoId) ?: throw IllegalStateException("الفيديو مش موجود")
        val job = TranslationJob(UUID.randomUUID().toString(), videoId, video.title,
            source.code, target.code, sttP.id, refiner.id, translatorP.id)
        add(job)
        startCloud(job)
        return job
    }

    @Synchronized internal fun startOffline(videoId: String, source: SubLang, target: SubLang, model: OfflineModel): Boolean {
        if (offlineWork.status.value != null || model !in OfflineModel.translators || target == SubLang.AUTO) return false
        val video = library.byId(videoId) ?: return false
        val job = TranslationJob(UUID.randomUUID().toString(), videoId, video.title, source.code, target.code,
            mode = TranslationMode.ON_DEVICE, offlineModel = model.id)
        add(job)
        startOffline(job)
        return true
    }

    private fun add(job: TranslationJob) {
        _jobs.value = listOf(job) + _jobs.value
        persistJobs()
    }

    @Synchronized fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.phase !in ACTIVE) return
        if (job.mode == TranslationMode.ON_DEVICE) offlineWork.cancel(OfflineWorkKind.SUBTITLES, id)
        else {
            cloudWorkers[id]?.cancel()
            patch(id) { it.copy(phase = TranslationPhase.CANCELLED) }
        }
    }

    @Synchronized fun delete(id: String) {
        if (offlineWork.status.value?.let { it.kind == OfflineWorkKind.SUBTITLES && it.key == id } == true || cloudWorkers.containsKey(id)) return
        _jobs.value = _jobs.value.filterNot { it.id == id }
        persistJobs()
        // Exported SRTs stay available on the video. Remove intermediate PCM/checkpoints.
        File(root, id).deleteRecursively()
    }

    @Synchronized fun resume(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id && it.phase in RESUMABLE } ?: return
        when (job.mode) {
            TranslationMode.ON_DEVICE -> startOffline(job)
            TranslationMode.CLOUD -> startCloud(job)
        }
    }

    private fun startOffline(job: TranslationJob) {
        if (offlineWork.status.value != null) return
        val model = OfflineModel.translator(job.offlineModel)
        val target = SubLang.of(job.targetLang)
        if (model == null || target == SubLang.AUTO) {
            failOffline(job.id, OfflineIssue.NOT_SUPPORTED)
            return
        }
        patch(job.id) { it.copy(phase = TranslationPhase.PREPARING, offlineIssue = null, message = null) }
        try {
            offlineWork.submit(OfflineWorkKind.SUBTITLES, job.id, onFinish = { error ->
                if (error != null) {
                    if (OfflineWork.cancelled(error)) patch(job.id) { it.copy(phase = TranslationPhase.CANCELLED) }
                    else failOffline(job.id, (error as? OfflineFailure)?.issue ?: OfflineIssue.SAVE)
                }
            }) { signal, notify ->
                val dir = File(root, job.id).apply { mkdirs() }
                val video = library.byId(job.videoId) ?: throw OfflineFailure(OfflineIssue.VIDEO)
                val input = File(video.localPath)
                if (video.kind == MediaKind.HLS || video.kind == MediaKind.DASH || input.extension.lowercase() in setOf("m3u8", "mpd")) {
                    throw OfflineFailure(OfflineIssue.AUDIO)
                }
                // Don't spend time decoding an entire video if a required model isn't installed.
                if ((!File(dir, "cues-original.json").exists() && !offlineModels.ready(OfflineModel.WHISPER_LARGE)) || !offlineModels.ready(model)) {
                    throw OfflineFailure(OfflineIssue.MISSING_MODELS)
                }
                val cues = offline.run(dir, input, SubLang.of(job.sourceLang), target, model, signal) { phase, progress, count ->
                    patch(job.id, progressOnly = true) { it.copy(phase = phase, progress = progress, cueCount = count) }
                    notify(progress)
                }
                signal.throwIfCanceled()
                patch(job.id) { it.copy(phase = TranslationPhase.SAVING, progress = 0.97) }
                CueJson.write(File(dir, "cues-translated-${target.code}.json"), cues)
                signal.throwIfCanceled()
                exportSubtitles(job, video.localPath, cues, target)
                patch(job.id) { it.copy(phase = TranslationPhase.DONE, progress = 1.0, cueCount = cues.size) }
                File(dir, "offline-mt-${model.id}-${target.code}").deleteRecursively()
            }
        } catch (e: OfflineFailure) { failOffline(job.id, e.issue) }
    }

    private fun failOffline(id: String, issue: OfflineIssue) = patch(id) {
        it.copy(phase = TranslationPhase.FAILED, offlineIssue = issue.name, message = null)
    }

    private fun startCloud(job: TranslationJob) {
        check(job.mode == TranslationMode.CLOUD)
        if (cloudWorkers.containsKey(job.id)) return
        patch(job.id) { it.copy(phase = TranslationPhase.PREPARING, message = null) }
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            val dir = File(root, job.id).apply { mkdirs() }
            try {
                val video = library.byId(job.videoId) ?: throw IllegalStateException("الفيديو اتحذف")
                // 1) Transcript reuse or STT
                var cues: List<SubCue>
                val transcriptFile = File(dir, "cues-original.json")
                if (transcriptFile.exists()) {
                    cues = CueJson.read(transcriptFile)
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
                    ensureActive()
                    CueJson.write(transcriptFile, cues)
                    audio.delete()
                }
                // 2) AI refinement (proofreading) — skippable
                val refinedFile = File(dir, "cues-refined.json")
                if (RefinerProvider.of(job.refinerProvider) != RefinerProvider.OFF && !refinedFile.exists()) {
                    patch(job.id) { it.copy(phase = TranslationPhase.REFINING, progress = 0.55, cueCount = cues.size) }
                    cues = refine(cues, job)
                    ensureActive()
                    CueJson.write(refinedFile, cues)
                }
                // 3) Contextual batch translation with failover, checkpoint per batch
                patch(job.id) { it.copy(phase = TranslationPhase.TRANSLATING, progress = 0.65, cueCount = cues.size) }
                val target = SubLang.of(job.targetLang)
                val checkpoint = File(dir, "cues-translated-${target.code}.json")
                val done = if (checkpoint.exists()) CueJson.read(checkpoint).associateBy { it.index } else emptyMap()
                val batchSize = 30
                cues.chunked(batchSize).forEachIndexed { bi, batch ->
                    ensureActive()
                    val pending = batch.filter { it.index !in done || done[it.index]?.translated.isNullOrBlank() }
                    if (pending.isNotEmpty()) {
                        val translated = translator.translateBatch(pending, target.englishName, TranslatorProvider.of(job.translatorProvider))
                        pending.forEachIndexed { i, c -> c.translated = translated.getOrElse(i) { c.original } }
                    }
                    val merged = cues.map { c -> done[c.index]?.takeIf { it.translated.isNotBlank() } ?: c }
                    ensureActive()
                    CueJson.write(checkpoint, merged)
                    patch(job.id) { it.copy(progress = (0.65 + 0.30 * minOf((bi + 1) * batchSize, cues.size) / cues.size.coerceAtLeast(1))) }
                }
                cues = CueJson.read(checkpoint)
                // 4) Three SRT outputs registered on the video.
                ensureActive()
                synchronized(this@TranslationManager) {
                    ensureActive()
                    patch(job.id) { it.copy(phase = TranslationPhase.SAVING, progress = 0.97) }
                    exportSubtitles(job, video.localPath, cues, target)
                    patch(job.id) { it.copy(phase = TranslationPhase.DONE, progress = 1.0, cueCount = cues.size, message = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@TranslationManager) {
                    ensureActive()
                    patch(job.id) { it.copy(phase = TranslationPhase.FAILED, message = e.message ?: "فشلت المهمة") }
                }
            }
        }
        cloudWorkers[job.id] = worker
        worker.invokeOnCompletion { synchronized(this) { cloudWorkers.remove(job.id) } }
        worker.start()
    }

    private val subtitleModes = mapOf("orig" to "original", "target" to "translated", "bilingual" to "bilingual")

    private fun subtitleDirectory(job: TranslationJob) = File(File(library.subtitlesDir, job.videoId), job.id)

    private fun exportSubtitles(job: TranslationJob, path: String, cues: List<SubCue>, target: SubLang) {
        val dir = subtitleDirectory(job).apply { mkdirs() }
        val files = subtitleModes.mapValues { (key, mode) ->
            File(dir, "$key.srt").also { atomicText(it, SrtCodec.write(cues, mode)) }.absolutePath
        }
        if (!library.attachSubtitles(job.videoId, path, files, target.code)) throw OfflineFailure(OfflineIssue.VIDEO)
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
        return if (src.exists()) runCatching { CueJson.read(src) }.getOrDefault(emptyList()) else emptyList()
    }

    fun saveCues(jobId: String, cues: List<SubCue>) {
        val job = _jobs.value.firstOrNull { it.id == jobId && it.phase == TranslationPhase.DONE } ?: return
        val dir = File(root, jobId).apply { mkdirs() }
        CueJson.write(File(dir, "cues-translated-${job.targetLang}.json"), cues)
        val owned = subtitleDirectory(job)
        val legacy = File(library.subtitlesDir, job.videoId)
        subtitleModes.forEach { (key, mode) ->
            // Never overwrite a different job's registered subtitles when reviewing an older job.
            val file = File(owned, "$key.srt").takeIf { it.isFile }
                ?: File(legacy, "$key.srt").takeIf { job.mode == TranslationMode.CLOUD && !owned.exists() && it.isFile }
            if (file != null) atomicText(file, SrtCodec.write(cues, mode))
        }
    }

    @Synchronized private fun patch(id: String, progressOnly: Boolean = false, f: (TranslationJob) -> TranslationJob) {
        var shouldPersist = !progressOnly
        _jobs.value = _jobs.value.map {
            if (it.id != id) it else f(it).also { updated -> shouldPersist = shouldPersist || it.phase != updated.phase }
        }
        if (shouldPersist) persistJobs()
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
                put("mode", j.mode.name); put("offlineModel", j.offlineModel ?: JSONObject.NULL)
                put("offlineIssue", j.offlineIssue ?: JSONObject.NULL)
            }) }
            atomicText(jobsFile, arr.toString())
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
                        .let { if (it in ACTIVE) TranslationPhase.PAUSED else it },
                    o.optDouble("progress", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0, o.optInt("cueCount"),
                    if (o.isNull("message")) null else o.getString("message"), o.optLong("createdAt"),
                    mode = TranslationMode.fromStored(if (o.has("mode")) o.optString("mode", "unknown") else null),
                    offlineModel = o.optString("offlineModel", "").takeIf { !o.isNull("offlineModel") && it.isNotBlank() },
                    offlineIssue = o.optString("offlineIssue", "").takeIf { !o.isNull("offlineIssue") && it.isNotBlank() })
            }
        }
    }
}
