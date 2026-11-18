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
    private val pcmDecoder: (File, File, CancellationSignal, (Double) -> Unit) -> Unit = ::decodeLocalPcm
) {
    fun run(
        dir: File, input: File, source: SubLang, target: SubLang, model: OfflineModel, signal: CancellationSignal,
        progress: (TranslationPhase, Double, Int) -> Unit
    ): List<SubCue> {
        signal.throwIfCanceled()
        val title = stamp(input)
        val fingerprint = File(dir, "offline-source.json")
        if (fingerprint.exists()) {
            if (fingerprint.readText() != title) throw OfflineFailure(OfflineIssue.VIDEO)
        } else atomicText(fingerprint, title)
        val original = File(dir, "cues-original.json")
        val langFile = File(dir, "offline-language.txt")
        if (!original.exists()) {
            val pcm = File(dir, "audio-16k.pcm")
            if (!pcm.exists()) {
                progress(TranslationPhase.EXTRACTING, 0.02, 0)
                val temp = File(dir, "audio-16k.pcm.tmp")
                try {
                    stage(OfflineIssue.AUDIO) {
                        pcmDecoder(input, temp, signal) { progress(TranslationPhase.EXTRACTING, 0.02 + 0.12 * it, 0) }
                    }
                    signal.throwIfCanceled()
                    if (!temp.renameTo(pcm)) throw OfflineFailure(OfflineIssue.SAVE)
                } finally { temp.delete() }
            }
            val (language, cues) = transcribe(dir, pcm, source, signal, progress)
            if (cues.isEmpty()) throw OfflineFailure(OfflineIssue.NO_SPEECH)
            signal.throwIfCanceled()
            atomicText(langFile, language)
            CueJson.write(original, cues)
            pcm.delete()
            File(dir, "offline-stt").deleteRecursively()
        }
        // Whisper context and all native speech tensors are already freed here.
        val cues = CueJson.read(original)
        val language = langFile.readText().trim()
        if (language !in hymtSourceLanguages) throw OfflineFailure(OfflineIssue.LANGUAGE)
        if (language == target.code) return cues.map { it.copy(translated = it.original) }
        return translate(dir, cues, model, target, signal, progress)
    }

    private fun stamp(file: File): String {
        if (!file.isFile || file.length() == 0L) throw OfflineFailure(OfflineIssue.VIDEO)
        return JSONObject().put("path", file.absolutePath).put("size", file.length()).put("mtime", file.lastModified()).toString()
    }

    private fun transcribe(
        dir: File, pcm: File, source: SubLang, signal: CancellationSignal,
        progress: (TranslationPhase, Double, Int) -> Unit
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
            if (!file.exists()) break
            val part = JSONObject(file.readText())
            language = part.getString("language")
            cues += CueJson.decode(part.getJSONArray("cues"))
            index++
        }
        fun report() = progress(TranslationPhase.TRANSCRIBING, 0.15 + 0.44 * index / count, cues.size)
        report()
        if (index < count) {
            val path = modelPath(OfflineModel.WHISPER_LARGE)
            val speech = stage(OfflineIssue.TRANSCRIPTION) { engines.speech(path, signal) }
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
                            val result = stage(OfflineIssue.TRANSCRIPTION) { engine.decode(samples, language, prompt) }
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
        dir: File, cues: List<SubCue>, model: OfflineModel, target: SubLang, signal: CancellationSignal,
        progress: (TranslationPhase, Double, Int) -> Unit
    ): List<SubCue> {
        val batches = planTranslation(cues)
        val work = File(dir, "offline-mt-${model.id}-${target.code}").apply { mkdirs() }
        val translated = mutableListOf<SubCue>()
        var index = 0
        while (index < batches.size) {
            signal.throwIfCanceled()
            val file = File(work, "$index.json")
            if (!file.exists()) break
            val batch = CueJson.read(file)
            if (batch.map { it.index } != batches[index].map { it.index } || batch.any { it.translated.isBlank() }) {
                throw OfflineFailure(OfflineIssue.SAVE)
            }
            translated += batch
            index++
        }
        fun report() = progress(TranslationPhase.TRANSLATING, 0.62 + 0.34 * translated.size / cues.size, cues.size)
        report()
        if (index < batches.size) {
            val path = modelPath(model)
            val engine = stage(OfflineIssue.TRANSLATION) { engines.translator(path, signal) }
            engine.use { llm ->
                val seed = Random.nextInt()
                while (index < batches.size) {
                    signal.throwIfCanceled()
                    val batch = batches[index]
                    val outputs = stage(OfflineIssue.TRANSLATION) {
                        translateLines(batch.map { it.original }, target.englishName, seed + index * 7919) { prompt, callSeed ->
                            signal.throwIfCanceled()
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

    private inline fun <T> stage(issue: OfflineIssue, body: () -> T): T {
        try { return body() }
        catch (e: OfflineFailure) { throw e }
        catch (e: OperationCanceledException) { throw e }
        catch (e: LinkageError) { throw OfflineFailure(OfflineIssue.NOT_SUPPORTED, e) }
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

    fun read(file: File) = decode(JSONArray(file.readText()))
    fun write(file: File, cues: List<SubCue>) = atomicText(file, encode(cues).toString())
}

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
