package com.ahcrazy.video2.core.dubbing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class SpeechPcm(val rate: Int, val samples: ShortArray)
internal enum class SpeechPcmHint { HEADER, FLOAT32, AZURE_PCM16, GEMINI_PCM16 }
private const val HEADER = 44L
private const val CHUNK_FRAMES = 8192
private const val MAX_SPEECH_BYTES = 32L * 1024 * 1024

private fun RandomAccessFile.u16() = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
private fun RandomAccessFile.u32() = Integer.toUnsignedLong(Integer.reverseBytes(readInt()))
private fun RandomAccessFile.tag() = ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)
internal fun monoPcm16Header(rate: Int, dataBytes: Long): ByteArray {
    require(rate > 0 && dataBytes in 2..(0xffff_ffffL - 36) && dataBytes % 2 == 0L)
    return ByteBuffer.allocate(HEADER.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + dataBytes).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
    }.array()
}

/** Accept PCM WAV output from different Android engines, including metadata and padded RIFF chunks. */
internal fun readSpeechWav(file: File, hint: SpeechPcmHint = SpeechPcmHint.HEADER, check: () -> Unit = {}): SpeechPcm = RandomAccessFile(file, "r").use { input ->
    fun invalid(): Nothing = throw DubFailure(DubIssue.AUDIO_FORMAT)
    if (input.length() !in HEADER..(MAX_SPEECH_BYTES + 1024 * 1024)) invalid()
    if (input.tag() != "RIFF") invalid()
    val riffSize = input.u32()
    // Some services deliver the complete RIFF in a stream with unknown lengths in its header.
    val streamed = hint == SpeechPcmHint.AZURE_PCM16 && riffSize == 0xffff_ffffL
    val end = if (streamed) input.length() else riffSize + 8
    if (end !in HEADER..input.length() || input.tag() != "WAVE") invalid()
    var encoding = 0
    var channels = 0
    var rate = 0
    var block = 0
    var bits = 0
    var dataAt = -1L
    var dataSize = 0L
    while (input.filePointer + 8 <= end) {
        val tag = input.tag()
        val chunkSize = input.u32()
        val start = input.filePointer
        val size = if (streamed && tag == "data" && chunkSize == 0xffff_ffffL) end - start else chunkSize
        if (size > end - start) invalid()
        if (tag == "fmt " && size >= 16 && encoding == 0) {
            encoding = input.u16()
            channels = input.u16()
            rate = input.u32().toInt()
            input.u32() // byte rate
            block = input.u16()
            bits = input.u16()
        } else if (tag == "data" && dataAt < 0) {
            dataAt = start
            dataSize = size
        }
        // Some engine writers omit the last padding byte on an odd-sized 8-bit PCM data chunk.
        input.seek((start + size + (size and 1)).coerceAtMost(end))
        if (dataAt >= 0 && encoding != 0) break
    }
    if (channels !in 1..2 || rate !in 8_000..96_000 ||
        !(encoding == 1 && bits in setOf(8, 16, 24, 32) || encoding == 3 && bits == 32) ||
        block != channels * (bits / 8) || dataAt < 0 || dataSize !in block.toLong()..MAX_SPEECH_BYTES ||
        dataSize % block != 0L || dataSize / block > rate * 90L) invalid()
    // Android's file synthesis callback can mark 32-bit floats as integer PCM in the WAV header.
    if (hint == SpeechPcmHint.FLOAT32 && bits != 32) invalid()
    if ((hint == SpeechPcmHint.AZURE_PCM16 || hint == SpeechPcmHint.GEMINI_PCM16) &&
        (encoding != 1 || channels != 1 || bits != 16 || rate != DUB_SAMPLE_RATE)) invalid()
    if (hint == SpeechPcmHint.GEMINI_PCM16 && end != input.length()) invalid()
    val floating = encoding == 3 || hint == SpeechPcmHint.FLOAT32
    val samples = ShortArray((dataSize / block).toInt())
    val bytes = ByteArray(CHUNK_FRAMES * block)
    val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    input.seek(dataAt)
    var i = 0
    while (i < samples.size) {
        check()
        val count = minOf(CHUNK_FRAMES, samples.size - i)
        input.readFully(bytes, 0, count * block)
        view.clear()
        repeat(count) {
            var sum = 0
            repeat(channels) {
                sum += when {
                    floating -> view.float.let { if (it.isFinite()) (it.coerceIn(-1f, 1f) * 32767).roundToInt() else 0 }
                    bits == 8 -> ((view.get().toInt() and 0xff) - 128) shl 8
                    bits == 16 -> view.short.toInt()
                    bits == 24 -> { view.get(); (view.get().toInt() and 0xff) or (view.get().toInt() shl 8) }
                    else -> view.int shr 16
                }
            }
            samples[i++] = (sum / channels).toShort()
        }
    }
    SpeechPcm(rate, samples)
}

/** Stream silence, then write/mix at exact frame offsets. No keeping an entire video or its cues in RAM. */
internal class TimedDubWav(private val out: RandomAccessFile, private val endFrame: Long, private val check: () -> Unit = {}) {
    private var written = 0L
    private val bytes = ByteArray(CHUNK_FRAMES * 2)
    private val zeros = ByteArray(CHUNK_FRAMES * 2)
    private val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(endFrame in 1..MAX_DUB_FRAMES)
        out.setLength(0)
        out.write(monoPcm16Header(DUB_SAMPLE_RATE, endFrame * 2))
    }

    private fun pad(until: Long) {
        if (until <= written) return
        out.seek(HEADER + written * 2)
        while (written < until) {
            check()
            val count = minOf(CHUNK_FRAMES.toLong(), until - written).toInt()
            out.write(zeros, 0, count * 2)
            written += count
        }
    }

    fun add(start: Long, pcm: ShortArray) {
        require(start >= 0 && start + pcm.size <= endFrame)
        pad(start)
        var read = 0
        while (read < pcm.size) {
            check()
            val count = minOf(CHUNK_FRAMES, pcm.size - read)
            val at = start + read
            val overlap = (written - at).coerceIn(0L, count.toLong()).toInt()
            val pos = HEADER + at * 2
            out.seek(pos)
            if (overlap > 0) out.readFully(bytes, 0, overlap * 2)
            view.clear()
            repeat(count) { i ->
                val prior = if (i < overlap) view.getShort(i * 2).toInt() else 0
                view.putShort((prior + pcm[read + i]).coerceIn(-32768, 32767).toShort())
            }
            out.seek(pos)
            out.write(bytes, 0, count * 2)
            read += count
            written = maxOf(written, at + count)
        }
    }

    fun finish() {
        pad(endFrame)
        out.fd.sync()
    }
}
