package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import androidx.annotation.Keep
import java.io.File

internal data class SpeechSegment(val startCs: Long, val endCs: Long, val text: String)
internal data class SpeechResult(val language: String, val segments: List<SpeechSegment>)

internal interface LocalSpeech : AutoCloseable {
    val vulkan: Boolean get() = false
    fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult
}
internal interface LocalTranslator : AutoCloseable {
    fun setOverlapping(value: Boolean) {}
    fun translate(prompt: String, seed: Int): String? = error("Prompt translation unavailable")
    fun translateBatch(lines: List<String>): List<String> = error("Direct translation unavailable")
}
internal interface LocalEngines {
    fun speech(model: File, signal: CancellationSignal): LocalSpeech
    fun speechMoonshine(model: File, signal: CancellationSignal): LocalSpeech = error("Moonshine unavailable")
    fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech = error("Vulkan backend unavailable")
    fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator
    fun translatorForOverlap(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator = translator(model, kind, signal)
}

internal object JniEngines : LocalEngines {
    private val threads get() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    private val whisper: WhisperNative by lazy {
        WhisperJni.ensureLoaded()
        if (WhisperJni.supportsFp16Dotprod()) {
            try {
                WhisperFp16DotprodJni.ensureLoaded()
                return@lazy WhisperFp16DotprodJni
            } catch (_: LinkageError) { /* Try the previous FP16/portable backends. */ }
        }
        if (WhisperJni.supportsFp16()) {
            try {
                WhisperFp16Jni.ensureLoaded()
                WhisperFp16Jni
            } catch (_: LinkageError) { WhisperJni }
        } else WhisperJni
    }

    override fun speech(model: File, signal: CancellationSignal) = openSpeech(model, signal, whisper, threads, false)

    override fun speechMoonshine(model: File, signal: CancellationSignal) = openMoonshine(model, signal)

    override fun speechVulkan(model: File, signal: CancellationSignal): LocalSpeech {
        WhisperVulkanJni.ensureLoaded()
        // Allow CPU helpers to coexist with the single MT worker; GPU does the heavy graph.
        return openSpeech(model, signal, WhisperVulkanJni, minOf(threads, 3), true)
    }

    private fun openSpeech(model: File, signal: CancellationSignal, native: WhisperNative, threads: Int, gpu: Boolean): LocalSpeech {
        val handle = native.create()
        try {
            signal.setOnCancelListener { native.cancel(handle) }
            signal.throwIfCanceled()
            native.load(handle, model.absolutePath.encodeToByteArray(), threads)
            return object : LocalSpeech {
                override val vulkan = gpu
                override fun decode(pcm: ShortArray, language: String, prompt: String,
                                    fast: Boolean, progress: WhisperProgress): SpeechResult {
                    val output = native.decode(handle, pcm, language.encodeToByteArray(), prompt.encodeToByteArray(), fast, progress)
                    val segments = output.drop(1).map { segment ->
                        val fields = segment.decodeToString().split('\t', limit = 3)
                        SpeechSegment(fields[0].toLong(), fields[1].toLong(), fields[2])
                    }
                    return SpeechResult(output[0].decodeToString(), segments)
                }
                override fun close() { signal.setOnCancelListener(null); native.destroy(handle) }
            }
        } catch (error: Throwable) {
            signal.setOnCancelListener(null)
            native.destroy(handle)
            throw error
        }
    }

    private val hymt: HymtNative by lazy {
        HymtJni.ensureLoaded()
        if (HymtJni.supportsDotprod()) {
            try {
                HymtDotprodJni.ensureLoaded()
                HymtDotprodJni
            } catch (_: LinkageError) { HymtJni }
        } else HymtJni
    }

    override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal) =
        if (kind.isFirefox) openFirefox(model, signal) else openTranslator(model, kind, signal, false)

    override fun translatorForOverlap(model: File, kind: OfflineModel, signal: CancellationSignal) =
        openTranslator(model, kind, signal, true)

    private fun openTranslator(model: File, kind: OfflineModel, signal: CancellationSignal, overlap: Boolean): LocalTranslator {
        require(kind in OfflineModel.translators && !kind.isFirefox)
        val native = hymt
        val full = threads
        val shared = minOf(full, 3)
        val handle = native.create()
        try {
            signal.setOnCancelListener { native.cancel(handle) }
            signal.throwIfCanceled()
            native.load(handle, model.absolutePath.encodeToByteArray(), if (overlap) shared else full, kind == OfflineModel.HYMT_LITE)
            return object : LocalTranslator {
                private var overlapping = overlap
                override fun setOverlapping(value: Boolean) {
                    if (value == overlapping) return
                    if (full != shared) native.setThreads(handle, if (value) shared else full)
                    overlapping = value
                }
                override fun translate(prompt: String, seed: Int): String? =
                    native.translate(handle, prompt.encodeToByteArray(), seed)?.decodeToString()
                override fun close() { signal.setOnCancelListener(null); native.destroy(handle) }
            }
        } catch (error: Throwable) {
            signal.setOnCancelListener(null)
            native.destroy(handle)
            throw error
        }
    }
}

@Keep
internal fun interface WhisperProgress {
    @Keep fun update(percent: Int)
}

internal interface WhisperNative {
    fun create(): Long
    fun cancel(handle: Long)
    fun destroy(handle: Long)
    fun load(handle: Long, path: ByteArray, threads: Int)
    fun decode(handle: Long, pcm: ShortArray, lang: ByteArray, previous: ByteArray, fast: Boolean, progress: WhisperProgress): Array<ByteArray>
}

@Keep
internal object WhisperJni : WhisperNative {
    private val library by lazy { System.loadLibrary("video2_whisper") }
    fun ensureLoaded() { library }
    external fun supportsFp16(): Boolean
    external fun supportsFp16Dotprod(): Boolean
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int)
    external override fun decode(handle: Long, pcm: ShortArray, lang: ByteArray, previous: ByteArray, fast: Boolean, progress: WhisperProgress): Array<ByteArray>
}

@Keep
internal object WhisperFp16Jni : WhisperNative {
    private val library by lazy { System.loadLibrary("video2_whisper_fp16") }
    fun ensureLoaded() { library }
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int)
    external override fun decode(handle: Long, pcm: ShortArray, lang: ByteArray, previous: ByteArray, fast: Boolean, progress: WhisperProgress): Array<ByteArray>
}

@Keep
internal object WhisperFp16DotprodJni : WhisperNative {
    private val library by lazy { System.loadLibrary("video2_whisper_fp16_dotprod") }
    fun ensureLoaded() { library }
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int)
    external override fun decode(handle: Long, pcm: ShortArray, lang: ByteArray, previous: ByteArray, fast: Boolean, progress: WhisperProgress): Array<ByteArray>
}

@Keep
internal object WhisperVulkanJni : WhisperNative {
    private val library by lazy { System.loadLibrary("video2_whisper_vulkan") }
    fun ensureLoaded() { library }
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int)
    external override fun decode(handle: Long, pcm: ShortArray, lang: ByteArray, previous: ByteArray, fast: Boolean, progress: WhisperProgress): Array<ByteArray>
}

internal interface HymtNative {
    fun create(): Long
    fun cancel(handle: Long)
    fun destroy(handle: Long)
    fun load(handle: Long, path: ByteArray, threads: Int, compact: Boolean)
    fun setThreads(handle: Long, threads: Int)
    fun translate(handle: Long, prompt: ByteArray, seed: Int): ByteArray?
}

@Keep
internal object HymtJni : HymtNative {
    private val library by lazy { System.loadLibrary("video2_hymt") }
    fun ensureLoaded() { library }
    external fun supportsDotprod(): Boolean
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int, compact: Boolean)
    external override fun setThreads(handle: Long, threads: Int)
    external override fun translate(handle: Long, prompt: ByteArray, seed: Int): ByteArray?
}

@Keep
internal object HymtDotprodJni : HymtNative {
    private val library by lazy { System.loadLibrary("video2_hymt_dotprod") }
    fun ensureLoaded() { library }
    external override fun create(): Long
    external override fun cancel(handle: Long)
    external override fun destroy(handle: Long)
    external override fun load(handle: Long, path: ByteArray, threads: Int, compact: Boolean)
    external override fun setThreads(handle: Long, threads: Int)
    external override fun translate(handle: Long, prompt: ByteArray, seed: Int): ByteArray?
}
