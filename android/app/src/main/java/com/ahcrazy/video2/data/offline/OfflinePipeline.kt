package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.AtomicFile
import com.ahcrazy.video2.core.model.GpuFallback
import com.ahcrazy.video2.core.model.OfflinePreset
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationPhase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/** Deliberately no provider clients or network dependency. A local failure is terminal until user retry. */
internal class OfflinePipeline(
    private val modelPath: (OfflineModel) -> File,
    private val engines: LocalEngines = JniEngines,
    private val checkTranslationMemory: () -> Unit = {},
    private val vulkanSupported: () -> Boolean = { false },
    private val vulkanHeadroom: (OfflineModel) -> Boolean = { false },
    private val overlapSupported: (OfflineModel, OfflineModel) -> Boolean = { _, _ -> false },
    private val overlapHeadroom: () -> Boolean = { false },
    private val pcmDecoder: (File, File, CancellationSignal, (Double) -> Unit) -> Unit = ::decodeLocalPcm
) {
    fun run(
        dir: File, input: File, source: SubLang, target: SubLang, speechModel: OfflineModel, signal: CancellationSignal,
        fastSpeech: Boolean = false, translator: OfflineModel = OfflineModel.HYMT_Q4,
        preset: OfflinePreset = OfflinePreset.CPU, stats: OfflineStats = OfflineStats(),
        progress: (TranslationPhase, Double, Int, Boolean) -> Unit
    ): List<SubCue> {
        require(speechModel in OfflineModel.speechModels && (!fastSpeech || speechModel.supportsFastSpeech))
        require(translator in OfflineModel.translators)
        if (!supportsLocalPair(source.code, target.code, speechModel, translator)) throw OfflineFailure(OfflineIssue.FAST_PAIR)
        signal.throwIfCanceled()
        val title = stamp(input)
        val fingerprint = File(dir, "offline-source.json")
        if (hasCheckpoint(fingerprint)) {
            if (readCheckpoint(fingerprint) != title) throw OfflineFailure(OfflineIssue.VIDEO)
        } else atomicText(fingerprint, title)
        val original = File(dir, "cues-original.json")
        val langFile = File(dir, "offline-language.txt")
        val pcm = File(dir, "audio-16k.pcm")
        val existingTranscript = hasCheckpoint(original)
        stats.reusedTranscript = existingTranscript
        var early: List<SubCue>? = null
        if (!existingTranscript) {
            if (pcm.isFile) stats.resumed = true
            if (!pcm.exists()) {
                progress(TranslationPhase.EXTRACTING, 0.02, 0, false)
                val temp = File(dir, "audio-16k.pcm.tmp")
                try {
                    stage(OfflineIssue.AUDIO) {
                        stats.measure(OfflineStats.Stage.EXTRACT) {
                            pcmDecoder(input, temp, signal) { progress(TranslationPhase.EXTRACTING, 0.02 + 0.12 * it, 0, false) }
                        }
                    }
                    signal.throwIfCanceled()
                    if (!temp.renameTo(pcm)) throw OfflineFailure(OfflineIssue.SAVE)
                } finally { temp.delete() }
            }
            early = transcribeAndStream(dir, pcm, original, langFile, source, target, speechModel, translator,
                fastSpeech, preset, stats, signal, progress)
        }
        // A process can stop after committing the transcript but before removing the temporary PCM.
        // Read and validate that checkpoint first; Whisper does not load again to translate it.
        signal.throwIfCanceled()
        val cues = CueJson.read(original)
        val language = detectedSourceCode(readCheckpoint(langFile)) ?: SubLang.AUTO.code
        pcm.delete()
        speechCheckpoint(dir, speechModel).deleteRecursively()
        if (language !in hymtSourceLanguages) throw OfflineFailure(OfflineIssue.LANGUAGE)
        if (!supportsLocalPair(language, target.code, speechModel, translator)) throw OfflineFailure(OfflineIssue.FAST_PAIR)
        if (language == target.code) {
            stats.sameLanguage = true
            return cues.map { it.copy(translated = it.original) }
        }
        if (early != null) return early
        // If only the final SRT export was interrupted, don't require either runtime again.
        val final = translatedCuesFile(dir, target.code, translator)
        if (hasCheckpoint(final)) {
            val saved = CueJson.read(final)
            if (matchesTranslation(cues, saved)) {
                // Interrupted export: no engine was rerun in this attempt.
                stats.resumed = true
                var consumed = 0
                var index = 0
                while (consumed < cues.size) {
                    val batch = checkNotNull(translationBatch(cues, consumed, true, translator))
                    consumed += batch.size
                    stats.batch(index++, fresh = false)
                }
                return saved
            }
        }
        return TranslatorSession(dir, target, translator, signal, stats).use { it.advance(cues, true, progress) }
    }

    /** A new translation job can reuse only the original timed transcript, never previous translated text. */
    fun reuseTranscript(from: File, to: File, input: File, signal: CancellationSignal, correctedSource: SubLang? = null) {
        val original = File(from, "cues-original.json")
        val language = File(from, "offline-language.txt")
        val fingerprint = File(from, "offline-source.json")
        if (listOf(original, language, fingerprint).any { !hasCheckpoint(it) }) throw OfflineFailure(OfflineIssue.NO_TRANSCRIPT)
        val current = stamp(input)
        if (readCheckpoint(fingerprint) != current) throw OfflineFailure(OfflineIssue.VIDEO)
        val cues = CueJson.read(original).map { it.copy(translated = "") }
        if (cues.isEmpty()) throw OfflineFailure(OfflineIssue.NO_TRANSCRIPT)
        signal.throwIfCanceled()
        atomicText(File(to, "offline-source.json"), current)
        val source = correctedSource?.code?.takeIf { it in hymtSourceLanguages } ?: readCheckpoint(language)
        atomicText(File(to, "offline-language.txt"), source)
        CueJson.write(File(to, "cues-original.json"), cues)
    }

    private fun stamp(file: File): String {
        if (!file.isFile || file.length() == 0L) throw OfflineFailure(OfflineIssue.VIDEO)
        return JSONObject().put("path", file.absolutePath).put("size", file.length()).put("mtime", file.lastModified()).toString()
    }

    private fun transcribeAndStream(
        dir: File, pcm: File, original: File, languageFile: File, source: SubLang, target: SubLang,
        speech: OfflineModel, translator: OfflineModel, fastSpeech: Boolean, preset: OfflinePreset,
        stats: OfflineStats, parent: CancellationSignal, progress: (TranslationPhase, Double, Int, Boolean) -> Unit
    ): List<SubCue>? {
        val speechSignal = CancellationSignal()
        val mtSignal = CancellationSignal()
        val active = AtomicReference<TranslationStream?>()
        parent.setOnCancelListener {
            speechSignal.cancel()
            mtSignal.cancel()
            active.get()?.stop()
        }
        try {
            val tryOverlap = preset == OfflinePreset.OVERLAP && !speech.isMoonshine && !translator.isFirefox && overlapSupported(speech, translator)
            if (preset == OfflinePreset.OVERLAP && !tryOverlap) stats.overlapDeferred = true
            fun stopOverlap() {
                active.getAndSet(null)?.let { stats.overlapDeferred = true; it.close() }
            }
            val (language, cues) = transcribe(dir, pcm, source, speech, fastSpeech, preset, stats, speechSignal, progress,
                onGpuLost = ::stopOverlap,
                onCommit = { language, prefix, gpu, more ->
                    if (tryOverlap && gpu && more && language in hymtSourceLanguages && language != target.code) {
                        val stream = active.get()
                        if (stream != null) {
                            // Check at speech boundaries too: MT can be idle with a loaded model.
                            if (overlapHeadroom()) stream.submit(prefix) else stopOverlap()
                        } else if (!mtSignal.isCanceled && nextTranslationBatch(prefix, 0, final = false) != null) {
                            if (overlapHeadroom()) {
                                val created = TranslationStream(mtSignal) { worker ->
                                    TranslatorSession(dir, target, translator, mtSignal, stats).use { mt ->
                                        var snapshot = worker.next()
                                        while (!snapshot.end) {
                                            mt.advance(snapshot.cues, false, progress, speechRunning = { !worker.sourceDone })
                                            snapshot = worker.next()
                                        }
                                        mt.advance(snapshot.cues, true, progress)
                                    }
                                }
                                created.start()
                                active.set(created)
                                if (mtSignal.isCanceled) created.stop()
                                created.submit(prefix)
                            } else {
                                stats.overlapDeferred = true
                                mtSignal.cancel()
                            }
                        }
                    }
                })
            active.get()?.finishSource()
            if (cues.isEmpty()) throw OfflineFailure(OfflineIssue.NO_SPEECH)
            parent.throwIfCanceled()
            atomicText(languageFile, language)
            CueJson.write(original, cues)
            AtomicFile(gpuMarker(dir)).delete()
            val stream = active.get() ?: return null
            stream.submit(cues, end = true)
            return try { stream.await() }
            catch (e: OfflineFailure) {
                parent.throwIfCanceled()
                if (e.issue == OfflineIssue.SAVE) throw e
                // Re-read only committed MT batches, after the early worker has closed its model.
                stats.overlapDeferred = true
                null
            }
        } finally {
            active.getAndSet(null)?.close()
            parent.setOnCancelListener(null)
        }
    }

    private fun gpuMarker(dir: File) = File(dir, "offline-vulkan-active")

    private fun transcribe(
        dir: File, pcm: File, source: SubLang, speechModel: OfflineModel, fastSpeech: Boolean, preset: OfflinePreset,
        stats: OfflineStats, signal: CancellationSignal, progress: (TranslationPhase, Double, Int, Boolean) -> Unit,
        onGpuLost: () -> Unit, onCommit: (String, List<SubCue>, Boolean, Boolean) -> Unit
    ): Pair<String, List<SubCue>> {
        val frames = pcm.length() / 2L
        if (frames <= 0 || pcm.length() % 2L != 0L) throw OfflineFailure(OfflineIssue.AUDIO)
        if (frames > MAX_LOCAL_SECONDS * SAMPLE_RATE.toLong()) throw OfflineFailure(OfflineIssue.TOO_LONG)
        stats.audioMs = frames * 1000 / SAMPLE_RATE
        val count = ((frames + SpeechWindow.CORE - 1) / SpeechWindow.CORE).toInt()
        val checkpoints = speechCheckpoint(dir, speechModel).apply { mkdirs() }
        val cues = mutableListOf<SubCue>()
        var language = source.code
        var triedLanguageRescue = false
        var index = 0
        while (index < count) {
            signal.throwIfCanceled()
            val file = File(checkpoints, "$index.json")
            if (!hasCheckpoint(file)) break
            val part = JSONObject(readCheckpoint(file))
            language = if (source == SubLang.AUTO) part.optString("language", "auto") else source.code
            triedLanguageRescue = triedLanguageRescue || part.optBoolean("autoSourceProbed")
            cues += CueJson.decode(part.getJSONArray("cues"))
            index++
        }
        fun report(fraction: Double = 0.0, checkpoint: Boolean = true) =
            progress(TranslationPhase.TRANSCRIBING, 0.15 + 0.44 * (index + fraction) / count, cues.size, checkpoint)
        report()
        if (index > 0) stats.resumed = true
        stats.reusedSpeechWindows = index
        if (index < count) {
            val marker = gpuMarker(dir)
            val path = modelPath(speechModel)
            var ownedMarker = false
            fun openCpu(): LocalSpeech = stage(OfflineIssue.TRANSCRIPTION, speechModel.memoryIssue) {
                // A failed GPU attempt may have changed available memory, even after it closes.
                val cpuPath = if (stats.gpuFallback == GpuFallback.ENGINE) modelPath(speechModel) else path
                stats.measure(OfflineStats.Stage.CPU_SPEECH) {
                    if (speechModel.isMoonshine) engines.speechMoonshine(cpuPath, signal) else engines.speech(cpuPath, signal)
                }
            }
            fun openGpu(): LocalSpeech? {
                if (preset == OfflinePreset.CPU || speechModel.isMoonshine) return null
                stats.gpuFallback = when {
                    hasCheckpoint(marker) -> GpuFallback.INTERRUPTED
                    !vulkanSupported() -> GpuFallback.UNAVAILABLE
                    !vulkanHeadroom(speechModel) -> GpuFallback.HEADROOM
                    else -> null
                }
                if (stats.gpuFallback != null) return null
                // Keep this on process death. A resumed attempt then uses CPU instead of looping on a driver crash.
                atomicText(marker, "vulkan")
                ownedMarker = true
                return try {
                    stage(OfflineIssue.TRANSCRIPTION, speechModel.memoryIssue) {
                        stats.measure(OfflineStats.Stage.GPU_SPEECH) { engines.speechVulkan(path, signal) }.let {
                            if (it.vulkan) it else { it.close(); error("GPU backend did not initialize") }
                        }
                    }
                } catch (error: OfflineFailure) {
                    signal.throwIfCanceled()
                    ownedMarker = false // retain marker across any further interruption in this job
                    stats.gpuFallback = GpuFallback.ENGINE
                    null
                }
            }
            var engine: LocalSpeech? = null
            try {
                engine = openGpu() ?: openCpu()
                RandomAccessFile(pcm, "r").use { audio ->
                    fun readSamples(start: Long, end: Long): ShortArray {
                        val bytes = ByteArray(((end - start) * 2).toInt())
                        audio.seek(start * 2)
                        audio.readFully(bytes)
                        return ShortArray(bytes.size / 2).also {
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                        }
                    }
                    while (index < count) {
                        signal.throwIfCanceled()
                        val window = SpeechWindow.at(index, frames)
                        val samples = readSamples(window.readStart, window.readEnd)
                        var added = emptyList<SubCue>()
                        if (samples.any { it != 0.toShort() }) {
                            val t = window.readStart.toDouble() / SAMPLE_RATE
                            val prompt = cues.takeLast(8).filter { it.endSec in (t - 8.0)..(t - 0.15) }
                                .joinToString(" ") { it.original }.takeLast(200)
                            var lastPercent = 0
                            fun infer(pcm: ShortArray, hint: String, previous: String, trackProgress: Boolean): SpeechResult {
                                fun decode(active: LocalSpeech) = stage(OfflineIssue.TRANSCRIPTION, speechModel.memoryIssue) {
                                    val stage = if (active.vulkan) OfflineStats.Stage.GPU_SPEECH else OfflineStats.Stage.CPU_SPEECH
                                    stats.measure(stage) {
                                        active.decode(pcm, hint, previous, fastSpeech) { value ->
                                            if (trackProgress) {
                                                val percent = value.coerceIn(0, 95)
                                                if (percent > lastPercent) {
                                                    lastPercent = percent
                                                    report(percent / 100.0, checkpoint = false)
                                                }
                                            }
                                        }
                                    }
                                }
                                val using = checkNotNull(engine)
                                return try { decode(using) }
                                catch (error: OfflineFailure) {
                                    signal.throwIfCanceled()
                                    if (!using.vulkan) throw error
                                    stats.gpuFallback = GpuFallback.ENGINE
                                    ownedMarker = false
                                    engine = null
                                    using.close()
                                    onGpuLost() // release MT before opening CPU Whisper
                                    openCpu().also { engine = it }.let(::decode)
                                }
                            }
                            val result = infer(samples, language, prompt, trackProgress = true)
                            var savedOnGpu = engine?.vulkan == true
                            added = cuesInWindow(window, result, cues.lastOrNull(), cues.size)
                            if (language == SubLang.AUTO.code && added.isNotEmpty()) {
                                val first = detectedSourceCode(result.language)
                                if (first != null && first in hymtSourceLanguages) language = first
                                else {
                                    if (!triedLanguageRescue) {
                                        val evidence = LanguageEvidence()
                                        for (slice in languageRescueSlices(window.readStart, frames)) {
                                            signal.throwIfCanceled()
                                            val part = readSamples(slice.start, slice.end)
                                            if (part.any { it != 0.toShort() }) {
                                                evidence.observe(infer(part, SubLang.AUTO.code, "", trackProgress = false), part.size)
                                            }
                                        }
                                        triedLanguageRescue = true
                                        evidence.supportedMajority()?.let { confirmed ->
                                            // Probe output itself is never saved. Re-decode the whole suspect window.
                                            val retry = infer(samples, confirmed, prompt, trackProgress = false)
                                            val corrected = cuesInWindow(window, retry, cues.lastOrNull(), cues.size)
                                            if (corrected.isNotEmpty()) {
                                                added = corrected
                                                language = confirmed
                                                savedOnGpu = engine?.vulkan == true
                                            }
                                        }
                                    }
                                    // If contradictory evidence remains, preserve the transcript and report the real gate later.
                                    if (language == SubLang.AUTO.code && first != null) language = first
                                }
                            }
                            if (savedOnGpu) stats.gpuWindows++ else stats.cpuWindows++
                        }
                        signal.throwIfCanceled()
                        atomicText(File(checkpoints, "$index.json"), JSONObject().put("language", language)
                            .put("autoSourceProbed", triedLanguageRescue).put("cues", CueJson.encode(added)).toString())
                        cues += added
                        index++
                        report()
                        onCommit(language, cues, engine?.vulkan == true, index < count)
                    }
                }
            } finally {
                engine?.close()
                if (ownedMarker) AtomicFile(marker).delete()
            }
        }
        return language to cues
    }

    private inner class TranslatorSession(
        dir: File, private val target: SubLang, private val model: OfflineModel,
        private val signal: CancellationSignal, private val stats: OfflineStats
    ) : AutoCloseable {
        private val work = translationCheckpoint(dir, target, model).apply { mkdirs() }
        private val translated = mutableListOf<SubCue>()
        private var index = 0
        private var engine: LocalTranslator? = null
        private val retryGuide = if (model == OfflineModel.HYMT_LITE) BatchRetryGuide() else null
        private val seed = 51 // Keep translation sampling comparable across CPU/GPU speech runs.

        fun advance(
            cues: List<SubCue>, final: Boolean, progress: (TranslationPhase, Double, Int, Boolean) -> Unit,
            speechRunning: () -> Boolean = { false }
        ): List<SubCue> {
            fun next() = translationBatch(cues, translated.size, final, model)
            fun report() {
                if (final) progress(TranslationPhase.TRANSLATING, 0.62 + 0.34 * translated.size / cues.size, cues.size, true)
            }
            fun checkOverlap(): Boolean {
                val overlapping = speechRunning()
                if (overlapping && !overlapHeadroom()) throw OfflineFailure(OfflineIssue.TRANSLATOR_MEMORY)
                return overlapping
            }
            while (true) {
                signal.throwIfCanceled()
                val batch = next() ?: break
                val file = File(work, "$index.json")
                if (!hasCheckpoint(file)) break
                val saved = CueJson.read(file)
                if (!matchesTranslation(batch, saved)) throw OfflineFailure(OfflineIssue.SAVE)
                translated += saved
                stats.batch(index++, fresh = false)
            }
            report()
            while (true) {
                signal.throwIfCanceled()
                val batch = next() ?: break
                val mt = engine ?: stage(OfflineIssue.TRANSLATION, OfflineIssue.TRANSLATOR_MEMORY) {
                    val overlapping = checkOverlap()
                    val path = modelPath(model)
                    stats.measure(OfflineStats.Stage.TRANSLATE) {
                        if (overlapping) engines.translatorForOverlap(path, model, signal) else engines.translator(path, model, signal)
                    }.also { engine = it }
                }
                val outputs = stage(OfflineIssue.TRANSLATION, OfflineIssue.TRANSLATOR_MEMORY) {
                    val originals = batch.map { it.original }
                    if (model.isFirefox) {
                        checkTranslationMemory()
                        stats.generationCalls++
                        stats.measure(OfflineStats.Stage.TRANSLATE) {
                            val output = mt.translateBatch(originals)
                            // This is a batch of independent model inputs, not prompt concatenation.
                            // Never commit a short or blank response or apply it to shifted timings.
                            if (output.size != batch.size) throw OfflineFailure(OfflineIssue.TRANSLATION)
                            output.mapIndexed { i, line ->
                                line.trim().ifBlank {
                                    originals[i].takeUnless { it.any(Char::isLetter) }
                                        ?: throw OfflineFailure(OfflineIssue.TRANSLATION)
                                }
                            }
                        }
                    } else {
                        val context = contextForBatch(cues, translated.size, batch.size)
                        translateLines(originals, target.englishName, seed + index * 7919, context, retryGuide) { prompt, callSeed ->
                            signal.throwIfCanceled()
                            val overlapping = checkOverlap()
                            checkTranslationMemory()
                            if (overlapping) stats.overlapped = true
                            stats.generationCalls++
                            stats.measure(OfflineStats.Stage.TRANSLATE) {
                                mt.setOverlapping(overlapping)
                                mt.translate(prompt, callSeed)
                            }
                        }
                    }
                }
                val done = batch.zip(outputs) { cue, text -> cue.copy(translated = text) }
                signal.throwIfCanceled()
                CueJson.write(File(work, "$index.json"), done)
                translated += done
                stats.batch(index++, fresh = true)
                report()
            }
            return if (final) translated.toList() else emptyList()
        }

        override fun close() { engine?.close() }
    }

    companion object {
        private fun speechCheckpoint(dir: File, speech: OfflineModel) =
            File(dir, if (speech.isMoonshine) "offline-stt-${speech.id}" else "offline-stt")

        // Same directory as older 7B Q4 jobs; never mix their batches with 1.8B.
        fun translatedCuesFile(dir: File, target: String, translator: OfflineModel?) = File(dir,
            if (translator == null || translator == OfflineModel.HYMT_Q4) "cues-translated-$target.json"
            else "cues-translated-${translator.id}-$target.json")

        fun translationCheckpoint(dir: File, target: SubLang, translator: OfflineModel = OfflineModel.HYMT_Q4) =
            File(dir, "offline-mt-${translator.id}-${target.code}")
    }

    private fun matchesTranslation(source: List<SubCue>, saved: List<SubCue>) =
        saved.size == source.size && saved.indices.all { i ->
            val cue = saved[i]
            cue.translated.isNotBlank() && cue.copy(translated = source[i].translated) == source[i]
        }

    private inline fun <T> stage(issue: OfflineIssue, memoryIssue: OfflineIssue = OfflineIssue.MEMORY, body: () -> T): T {
        try { return body() }
        catch (e: OfflineFailure) { throw e }
        catch (e: OperationCanceledException) { throw e }
        catch (e: LinkageError) { throw OfflineFailure(OfflineIssue.NOT_SUPPORTED, e) }
        catch (e: OutOfMemoryError) { throw OfflineFailure(memoryIssue, e) }
        catch (e: Exception) { throw OfflineFailure(issue, e) }
    }
}

internal object CueJson {
    fun encode(cues: List<SubCue>) = JSONArray().also { array -> cues.forEach { cue ->
        array.put(JSONObject().put("i", cue.index).put("s", cue.startSec).put("e", cue.endSec)
            .put("o", cue.original).put("t", cue.translated))
    } }

    fun decode(array: JSONArray): List<SubCue> = (0 until array.length()).map { i ->
        val cue = array.getJSONObject(i)
        SubCue(cue.getInt("i"), cue.getDouble("s"), cue.getDouble("e"), cue.getString("o"), cue.optString("t"))
    }

    fun read(file: File) = decode(JSONArray(readCheckpoint(file)))
    fun write(file: File, cues: List<SubCue>) = atomicText(file, encode(cues).toString())
}

internal fun hasCheckpoint(file: File) = file.isFile || File("${file.path}.bak").isFile
internal fun readCheckpoint(file: File) = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

internal fun atomicText(file: File, text: String) {
    val atomic = AtomicFile(file)
    val output = try { atomic.startWrite() } catch (e: IOException) { throw OfflineFailure(OfflineIssue.SAVE, e) }
    try {
        output.write(text.encodeToByteArray())
        atomic.finishWrite(output)
    } catch (e: IOException) {
        atomic.failWrite(output)
        throw OfflineFailure(OfflineIssue.SAVE, e)
    }
}
