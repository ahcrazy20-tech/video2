package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import androidx.annotation.Keep
import java.io.File

internal data class SpeechSegment(val startCs: Long, val endCs: Long, val text: String)
internal data class SpeechResult(val language: String, val segments: List<SpeechSegment>)

internal interface LocalSpeech : AutoCloseable {
    fun decode(pcm: ShortArray, language: String, prompt: String): SpeechResult
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

    override fun speech(model: File, signal: CancellationSignal): LocalSpeech {
        WhisperJni.ensureLoaded()
        val handle = WhisperJni.create()
        try {
            signal.setOnCancelListener { WhisperJni.cancel(handle) }
            signal.throwIfCanceled()
            WhisperJni.load(handle, model.absolutePath.encodeToByteArray(), threads)
            return object : LocalSpeech {
                override fun decode(pcm: ShortArray, language: String, prompt: String): SpeechResult {
                    val output = WhisperJni.decode(handle, pcm, language.encodeToByteArray(), prompt.encodeToByteArray())
                    val segments = output.drop(1).map { segment ->
                        val fields = segment.decodeToString().split('\t', limit = 3)
                        SpeechSegment(fields[0].toLong(), fields[1].toLong(), fields[2])
                    }
                    return SpeechResult(output[0].decodeToString(), segments)
                }
                override fun close() { signal.setOnCancelListener(null); WhisperJni.destroy(handle) }
            }
        } catch (error: Throwable) {
            signal.setOnCancelListener(null)
            WhisperJni.destroy(handle)
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
internal object WhisperJni {
    private val library by lazy { System.loadLibrary("video2_whisper") }
    fun ensureLoaded() { library }
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun load(handle: Long, path: ByteArray, threads: Int)
    external fun decode(handle: Long, pcm: ShortArray, source: ByteArray, previous: ByteArray): Array<ByteArray>
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
