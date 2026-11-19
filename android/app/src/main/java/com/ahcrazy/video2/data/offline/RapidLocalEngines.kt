package com.ahcrazy.video2.data.offline

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import android.os.CancellationSignal
import io.github.yinvoker.foxlet.NativeBridge
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

internal fun openMoonshine(model: File, signal: CancellationSignal): LocalSpeech {
    verifyBundle(model, OfflineModel.MOONSHINE_EN, signal)
    JNI.ensureLibraryLoaded()
    val options = arrayOf(
        TranscriberOption("return_audio_data", "false"),
        TranscriberOption("word_timestamps", "false"),
        TranscriberOption("identify_speakers", "false"),
        TranscriberOption("decode_incomplete_lines", "false"),
        TranscriberOption("vad_max_segment_duration", "8.0")
    )
    val handle = JNI.moonshineLoadTranscriberFromFiles(model.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING, options)
    check(handle >= 0) { "Cannot load Moonshine" }
    return object : LocalSpeech {
        override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
            require(language == "en")
            signal.throwIfCanceled()
            // Freeze each bounded file window in a fresh stream. A live pass on each
            // audio fragment would repeatedly decode partial text; reused streams
            // can carry VAD history across windows (Moonshine issue #231).
            val stream = JNI.moonshineCreateStream(handle, 0)
            check(stream >= 0) { "Cannot create file speech stream" }
            val maxCs = pcm.size * 100L / SAMPLE_RATE
            val segments = try {
                check(JNI.moonshineStartStream(handle, stream) == 0)
                val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
                check(JNI.moonshineAddAudioToStream(handle, stream, samples, SAMPLE_RATE, 0) == 0)
                check(JNI.moonshineStopStream(handle, stream) == 0)
                signal.throwIfCanceled()
                val transcript = checkNotNull(JNI.moonshineTranscribeStream(handle, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE))
                transcript.lines.orEmpty().mapNotNull { line ->
                    if (!line.isComplete) null else timedSpeechLine(line.startTime.toDouble(), line.duration.toDouble(), line.text.orEmpty(), maxCs)
                }.sortedBy { it.startCs }
            } finally {
                JNI.moonshineFreeStream(handle, stream)
            }
            signal.throwIfCanceled()
            return SpeechResult("en", segments)
        }

        override fun close() { JNI.moonshineFreeTranscriber(handle) }
    }
}

/** Use the native utterance bounds, never guessed word times. Reject non-finite or empty lines. */
internal fun timedSpeechLine(start: Double, duration: Double, text: String, maxCs: Long): SpeechSegment? {
    val end = start + duration
    if (!start.isFinite() || !duration.isFinite() || !end.isFinite() || duration <= 0 || text.isBlank()) return null
    val from = (start.coerceIn(0.0, maxCs / 100.0) * 100).roundToLong()
    val to = (end.coerceIn(0.0, maxCs / 100.0) * 100).roundToLong()
    return if (to > from) SpeechSegment(from, to, text.trim()) else null
}

internal fun openFirefox(model: File, signal: CancellationSignal): LocalTranslator {
    verifyBundle(model, OfflineModel.FIREFOX_EN_AR, signal)
    NativeBridge.ensureLoaded()
    // One worker replicates nothing. The model batches separate inputs into the
    // same inference pass and ARM kernels pick the best available instruction set.
    val service = NativeBridge.createService(1, 0)
    check(service != 0L) { "Cannot initialize Firefox Translations" }
    val handle = try {
        signal.throwIfCanceled()
        NativeBridge.loadModel(service, firefoxConfig(model), null).also { check(it != 0L) }
    } catch (error: Throwable) {
        NativeBridge.destroyService(service)
        throw error
    }
    return object : LocalTranslator {
        override fun translateBatch(lines: List<String>): List<String> {
            signal.throwIfCanceled()
            return NativeBridge.translate(service, handle, lines.toTypedArray(), false).toList().also { signal.throwIfCanceled() }
        }

        override fun close() {
            try { NativeBridge.releaseModel(service, handle) }
            finally { NativeBridge.destroyService(service) }
        }
    }
}

private fun firefoxConfig(dir: File): String {
    fun path(name: String) = JSONObject.quote(File(dir, name).absolutePath)
    val vocab = path("vocab.enar.spm")
    return """
        models:
          - ${path("model.enar.intgemm.alphas.bin")}
        vocabs:
          - $vocab
          - $vocab
        shortlist:
          - ${path("lex.50.50.enar.s2t.bin")}
          - 1
        beam-size: 1
        normalize: 1.0
        word-penalty: 0
        max-length-break: 128
        mini-batch-words: 512
        max-length-factor: 2.0
        skip-cost: true
        gemm-precision: int8shiftAlphaAll
        alignment: soft
        check-bytearray: true
        ssplit-language: en
        quiet: true
        quiet-translation: true
    """.trimIndent()
}

private fun verifyBundle(dir: File, model: OfflineModel, signal: CancellationSignal) {
    // Check the complete, app-private bundle before exposing its files to native parsers.
    for (asset in model.assets) {
        signal.throwIfCanceled()
        val file = File(dir, asset.name)
        if (!file.isFile) throw OfflineFailure(OfflineIssue.MISSING_MODELS)
        file.inputStream().use { copyVerifiedWeight(asset.spec, it, null, signal) {} }
    }
}
