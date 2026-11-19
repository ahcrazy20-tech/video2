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

internal fun openFirefox(model: File, route: OfflineModel, signal: CancellationSignal, arabic: File? = null): LocalTranslator {
    val source = requireNotNull(route.firefoxSource)
    require(route.isFirefoxPivot == (arabic != null))
    verifyBundle(model, route, signal)
    if (arabic != null) verifyBundle(arabic, OfflineModel.FIREFOX_EN_AR, signal)
    NativeBridge.ensureLoaded()
    // The upstream pivot API keeps both models resident and keeps response order. No English
    // intermediate is written to the library or reused as a source transcript.
    val service = NativeBridge.createService(1, 0)
    check(service != 0L) { "Cannot initialize Firefox Translations" }
    var first = 0L
    var second = 0L
    fun releaseHandles() {
        try { if (second != 0L) NativeBridge.releaseModel(service, second) }
        finally {
            try { if (first != 0L) NativeBridge.releaseModel(service, first) }
            finally { NativeBridge.destroyService(service) }
        }
    }
    fun load(dir: File, from: String, to: String): Long {
        signal.throwIfCanceled()
        return NativeBridge.loadModel(service, firefoxConfig(dir, from, to), null).also { check(it != 0L) }
    }
    try {
        first = load(model, source, if (arabic == null) "ar" else "en")
        if (arabic != null) second = load(arabic, "en", "ar")
        signal.throwIfCanceled()
    } catch (error: Throwable) {
        try { releaseHandles() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }
    return object : LocalTranslator {
        override fun translateBatch(lines: List<String>): List<String> {
            signal.throwIfCanceled()
            val array = lines.toTypedArray()
            val result = if (second == 0L) NativeBridge.translate(service, first, array, false)
                else NativeBridge.translatePivot(service, first, second, array, false)
            signal.throwIfCanceled()
            return result.toList()
        }

        override fun close() = releaseHandles()
    }
}

private fun firefoxConfig(dir: File, from: String, to: String): String {
    fun path(name: String) = JSONObject.quote(File(dir, name).absolutePath)
    val pair = from + to
    val vocab = path("vocab.$pair.spm")
    return """
        models:
          - ${path("model.$pair.intgemm.alphas.bin")}
        vocabs:
          - $vocab
          - $vocab
        shortlist:
          - ${path("lex.50.50.$pair.s2t.bin")}
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
        ssplit-language: $from
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
