package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.AtomicFile
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
import kotlin.random.Random

/** Deliberately no provider clients or network dependency. A local failure is terminal until user retry. */
internal class OfflinePipeline(
    private val modelPath: (OfflineModel) -> File,
    private val engines: LocalEngines = JniEngines,
    private val checkTranslationMemory: () -> Unit = {},
    private val pcmDecoder: (File, File, CancellationSignal, (Double) -> Unit) -> Unit = ::decodeLocalPcm
) {
    fun run(
        dir: File, input: File, source: SubLang, target: SubLang, speechModel: OfflineModel, signal: CancellationSignal,
        fastSpeech: Boolean = false, progress: (TranslationPhase, Double, Int, Boolean) -> Unit
    ): List<SubCue> {
        require(speechModel in OfflineModel.speechModels && (!fastSpeech || speechModel == OfflineModel.WHISPER_MEDIUM))
        signal.throwIfCanceled()
        val title = stamp(input)
        val fingerprint = File(dir, "offline-source.json")
        if (hasCheckpoint(fingerprint)) {
            if (readCheckpoint(fingerprint) != title) throw OfflineFailure(OfflineIssue.VIDEO)
        } else atomicText(fingerprint, title)
        val original = File(dir, "cues-original.json")
        val langFile = File(dir, "offline-language.txt")
        val pcm = File(dir, "audio-16k.pcm")
        if (!hasCheckpoint(original)) {
            if (!pcm.exists()) {
                progress(TranslationPhase.EXTRACTING, 0.02, 0, false)
                val temp = File(dir, "audio-16k.pcm.tmp")
                try {
                    stage(OfflineIssue.AUDIO) {
                        pcmDecoder(input, temp, signal) { progress(TranslationPhase.EXTRACTING, 0.02 + 0.12 * it, 0, false) }
                    }
                    signal.throwIfCanceled()
                    if (!temp.renameTo(pcm)) throw OfflineFailure(OfflineIssue.SAVE)
                } finally { temp.delete() }
            }
            val (language, cues) = transcribe(dir, pcm, source, speechModel, fastSpeech, signal, progress)
            if (cues.isEmpty()) throw OfflineFailure(OfflineIssue.NO_SPEECH)
            signal.throwIfCanceled()
            atomicText(langFile, language)
            CueJson.write(original, cues)
        }
        // A process can stop after committing the transcript but before removing the temporary PCM.
        // Read and validate that checkpoint first; Whisper does not load again to translate it.
        val cues = CueJson.read(original)
        val language = readCheckpoint(langFile).trim()
        pcm.delete()
        File(dir, "offline-stt").deleteRecursively()
        if (language !in hymtSourceLanguages) throw OfflineFailure(OfflineIssue.LANGUAGE)
        if (language == target.code) return cues.map { it.copy(translated = it.original) }
        // If only the final SRT export was interrupted, don't require either runtime again.
        val final = File(dir, "cues-translated-${target.code}.json")
        if (hasCheckpoint(final)) {
            val saved = CueJson.read(final)
            if (matchesTranslation(cues, saved)) return saved
        }
        return translate(dir, cues, target, signal, progress)
    }

    private fun stamp(file: File): String {
        if (!file.isFile || file.length() == 0L) throw OfflineFailure(OfflineIssue.VIDEO)
        return JSONObject().put("path", file.absolutePath).put("size", file.length()).put("mtime", file.lastModified()).toString()
    }

    private fun transcribe(
        dir: File, pcm: File, source: SubLang, speechModel: OfflineModel, fastSpeech: Boolean, signal: CancellationSignal,
        progress: (TranslationPhase, Double, Int, Boolean) -> Unit
    ): Pair<String, List<SubCue>> {
        val frames = pcm.length() / 2L
        if (frames <= 0 || pcm.length() % 2L != 0L) throw OfflineFailure(OfflineIssue.AUDIO)
        if (frames > MAX_LOCAL_SECONDS * SAMPLE_RATE.toLong()) throw OfflineFailure(OfflineIssue.TOO_LONG)
        val count = ((frames + SpeechWindow.CORE - 1) / SpeechWindow.CORE).toInt()
        val checkpoints = File(dir, "offline-stt").apply { mkdirs() }
        val cues = mutableListOf<SubCue>()
        var language = source.code
        var index = 0
        while (index < count) {
            signal.throwIfCanceled()
            val file = File(checkpoints, "$index.json")
            if (!hasCheckpoint(file)) break
            val part = JSONObject(readCheckpoint(file))
            language = part.getString("language")
            cues += CueJson.decode(part.getJSONArray("cues"))
            index++
        }
        fun report(fraction: Double = 0.0, checkpoint: Boolean = true) =
            progress(TranslationPhase.TRANSCRIBING, 0.15 + 0.44 * (index + fraction) / count, cues.size, checkpoint)
        report()
        if (index < count) {
            val path = modelPath(speechModel)
            val speech = stage(OfflineIssue.TRANSCRIPTION, speechModel.memoryIssue) { engines.speech(path, signal) }
            speech.use { engine ->
                RandomAccessFile(pcm, "r").use { audio ->
                    while (index < count) {
                        signal.throwIfCanceled()
                        val window = SpeechWindow.at(index, frames)
                        val bytes = ByteArray(((window.readEnd - window.readStart) * 2).toInt())
                        audio.seek(window.readStart * 2)
                        audio.readFully(bytes)
                        val samples = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        var added = emptyList<SubCue>()
                        if (samples.any { it != 0.toShort() }) {
                            val t = window.readStart.toDouble() / SAMPLE_RATE
                            // Only pass previous text that isn't already in the acoustic overlap.
                            val prompt = cues.takeLast(8).filter { it.endSec in (t - 8.0)..(t - 0.15) }
                                .joinToString(" ") { it.original }.takeLast(200)
                            val result = stage(OfflineIssue.TRANSCRIPTION, speechModel.memoryIssue) {
                                var lastPercent = 0
                                engine.decode(samples, language, prompt, fastSpeech) { value ->
                                    // UI-only until the whole window is atomically saved. Ignore repeat/regressing updates.
                                    val percent = value.coerceIn(0, 95)
                                    if (percent > lastPercent) {
                                        lastPercent = percent
                                        report(percent / 100.0, checkpoint = false)
                                    }
                                }
                            }
                            added = cuesInWindow(window, result, cues.lastOrNull(), cues.size)
                            if (language == "auto" && added.isNotEmpty() && result.language.isNotBlank()) language = result.language
                        }
                        signal.throwIfCanceled()
                        atomicText(File(checkpoints, "$index.json"), JSONObject().put("language", language)
                            .put("cues", CueJson.encode(added)).toString())
                        cues += added
                        index++
                        report()
                    }
                }
            }
        }
        return language to cues
    }

    private fun translate(
        dir: File, cues: List<SubCue>, target: SubLang, signal: CancellationSignal,
        progress: (TranslationPhase, Double, Int, Boolean) -> Unit
    ): List<SubCue> {
        val batches = planTranslation(cues)
        val work = translationCheckpoint(dir, target).apply { mkdirs() }
        val translated = mutableListOf<SubCue>()
        var index = 0
        while (index < batches.size) {
            signal.throwIfCanceled()
            val file = File(work, "$index.json")
            if (!hasCheckpoint(file)) break
            val batch = CueJson.read(file)
            if (!matchesTranslation(batches[index], batch)) throw OfflineFailure(OfflineIssue.SAVE)
            translated += batch
            index++
        }
        fun report() = progress(TranslationPhase.TRANSLATING, 0.62 + 0.34 * translated.size / cues.size, cues.size, true)
        report()
        if (index < batches.size) {
            val path = modelPath(OfflineModel.HYMT_Q4)
            val engine = stage(OfflineIssue.TRANSLATION, OfflineIssue.TRANSLATOR_MEMORY) { engines.translator(path, signal) }
            engine.use { llm ->
                val seed = Random.nextInt()
                while (index < batches.size) {
                    signal.throwIfCanceled()
                    val batch = batches[index]
                    val outputs = stage(OfflineIssue.TRANSLATION, OfflineIssue.TRANSLATOR_MEMORY) {
                        val context = contextForBatch(cues, translated.size, batch.size)
                        translateLines(batch.map { it.original }, target.englishName, seed + index * 7919, context) { prompt, callSeed ->
                            signal.throwIfCanceled()
                            checkTranslationMemory()
                            llm.translate(prompt, callSeed)
                        }
                    }
                    val done = batch.zip(outputs) { cue, text -> cue.copy(translated = text) }
                    signal.throwIfCanceled()
                    CueJson.write(File(work, "$index.json"), done)
                    translated += done
                    index++
                    report()
                }
            }
        }
        return translated
    }

    companion object {
        // Same directory as 1.7 Q4 jobs so their finished batches are reusable.
        fun translationCheckpoint(dir: File, target: SubLang) = File(dir, "offline-mt-${OfflineModel.HYMT_Q4.id}-${target.code}")
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
