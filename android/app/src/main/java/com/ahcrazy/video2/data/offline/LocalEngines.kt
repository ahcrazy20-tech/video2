package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import androidx.annotation.Keep
import java.io.File

internal data class SpeechSegment(val startCs: Long, val endCs: Long, val text: String)
internal data class SpeechResult(val language: String, val segments: List<SpeechSegment>)

internal interface LocalSpeech : AutoCloseable {
    fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult
}
internal interface LocalTranslator : AutoCloseable {
    fun translate(prompt: String, seed: Int): String?
}
internal interface LocalEngines {
    fun speech(model: File, signal: CancellationSignal): LocalSpeech
    fun translator(model: File, signal: CancellationSignal): LocalTranslator
}

internal object JniEngines : LocalEngines {
    private val threads get() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    private val whisper: WhisperNative by lazy {
        WhisperJni.ensureLoaded()
        if (WhisperJni.supportsFp16()) {
            try {
                WhisperFp16Jni.ensureLoaded()
                WhisperFp16Jni
            } catch (_: LinkageError) { WhisperJni }
        } else WhisperJni
    }

    override fun speech(model: File, signal: CancellationSignal): LocalSpeech {
        val native = whisper
        val handle = native.create()
        try {
            signal.setOnCancelListener { native.cancel(handle) }
            signal.throwIfCanceled()
            native.load(handle, model.absolutePath.encodeToByteArray(), threads)
            return object : LocalSpeech {
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

    override fun translator(model: File, signal: CancellationSignal): LocalTranslator {
        HymtJni.ensureLoaded()
        val handle = HymtJni.create()
        try {
            signal.setOnCancelListener { HymtJni.cancel(handle) }
            signal.throwIfCanceled()
            HymtJni.load(handle, model.absolutePath.encodeToByteArray(), threads)
            return object : LocalTranslator {
                override fun translate(prompt: String, seed: Int): String? =
                    HymtJni.translate(handle, prompt.encodeToByteArray(), seed)?.decodeToString()
                override fun close() { signal.setOnCancelListener(null); HymtJni.destroy(handle) }
            }
        } catch (error: Throwable) {
            signal.setOnCancelListener(null)
            HymtJni.destroy(handle)
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
internal object HymtJni {
    private val library by lazy { System.loadLibrary("video2_hymt") }
    fun ensureLoaded() { library }
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun load(handle: Long, path: ByteArray, threads: Int)
    external fun translate(handle: Long, input: ByteArray, seed: Int): ByteArray?
}
