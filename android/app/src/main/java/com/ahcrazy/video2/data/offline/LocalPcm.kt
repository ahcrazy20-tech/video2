package com.ahcrazy.video2.data.offline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.CancellationSignal
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val SAMPLE_RATE = 16_000
internal const val MAX_LOCAL_SECONDS = 12 * 3600

/** Only a local file descriptor is given to the platform decoder. No URL, HLS or network data source. */
internal fun decodeLocalPcm(input: File, output: File, signal: CancellationSignal, progress: (Double) -> Unit) {
    val extractor = MediaExtractor()
    try {
        FileInputStream(input).use { source ->
            extractor.setDataSource(source.fd)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw OfflineFailure(OfflineIssue.AUDIO)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw OfflineFailure(OfflineIssue.AUDIO)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_LOCAL_SECONDS * 1_000_000L) throw OfflineFailure(OfflineIssue.TOO_LONG)
            val pcmBytes = durationUs.coerceAtLeast(0L) * SAMPLE_RATE * 2 / 1_000_000L
            if (output.parentFile!!.usableSpace < pcmBytes + 128 * MIB) throw OfflineFailure(OfflineIssue.STORAGE)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                output.outputStream().use { stream ->
                    val sink = TimedPcmSink(stream)
                    val info = MediaCodec.BufferInfo()
                    var spec: PcmFormat? = null
                    var inputDone = false
                    var outputDone = false
                    var lastActivity = SystemClock.elapsedRealtime()
                    var lastUpdate = 0L
                    while (!outputDone) {
                        signal.throwIfCanceled()
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(5_000)
                            if (index >= 0) {
                                val buffer = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
                                val count = extractor.readSampleData(buffer, 0)
                                if (count < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    // DRM tracks and incomplete compressed frames must not look like speech.
                                    if (extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                        throw OfflineFailure(OfflineIssue.AUDIO)
                                    }
                                    codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                                lastActivity = SystemClock.elapsedRealtime()
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, 10_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            spec = PcmFormat.from(codec.outputFormat)
                            lastActivity = SystemClock.elapsedRealtime()
                        } else if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val pcm = spec ?: PcmFormat.from(codec.outputFormat).also { spec = it }
                                    sink.append(buffer, pcm, info.presentationTimeUs)
                                    val now = SystemClock.elapsedRealtime()
                                    if (durationUs > 0 && now - lastUpdate >= 500) {
                                        lastUpdate = now
                                        progress((info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0))
                                    }
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { codec.releaseOutputBuffer(index, false) }
                            lastActivity = SystemClock.elapsedRealtime()
                        } else if (SystemClock.elapsedRealtime() - lastActivity > 20_000) {
                            throw OfflineFailure(OfflineIssue.AUDIO)
                        }
                    }
                    sink.finish()
                    if (!sink.hasAudio) throw OfflineFailure(OfflineIssue.AUDIO)
                    progress(1.0)
                }
            } finally { codec.release() }
        }
    } finally { extractor.release() }
}

internal data class PcmFormat(val rate: Int, val channels: Int, val encoding: Int) {
    val sampleBytes = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> throw OfflineFailure(OfflineIssue.AUDIO)
    }

    init {
        if (rate !in 8_000..384_000 || channels !in 1..32) throw OfflineFailure(OfflineIssue.AUDIO)
    }

    fun sample(buffer: ByteBuffer): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
            value / 8_388_608f
        }
        AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
        else -> buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
    }

    companion object {
        fun from(format: MediaFormat) = PcmFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT)
    }
}

/** Keep decoder PTS on the video timeline (including an initial audio delay and real gaps). */
internal class TimedPcmSink(private val output: OutputStream) {
    private val buffer = ByteArray(64 * 1024)
    private var size = 0
    private var written = 0L
    private var next = 0L
    private var regionPts = 0L
    private var inputFrames = 0L
    private var format: PcmFormat? = null
    private var resampler: SpeechResampler? = null
    var hasAudio = false
        private set

    fun append(pcm: ByteBuffer, spec: PcmFormat, ptsUs: Long) {
        if (ptsUs !in -1_000_000L..MAX_LOCAL_SECONDS * 1_000_000L) throw OfflineFailure(OfflineIssue.TOO_LONG)
        val frameBytes = spec.channels * spec.sampleBytes
        if (pcm.remaining() % frameBytes != 0) throw OfflineFailure(OfflineIssue.AUDIO)
        if (format != spec || abs(ptsUs - (regionPts + inputFrames * 1_000_000L / spec.rate)) > 80_000) {
            resampler?.finish()
            next = ptsUs * SAMPLE_RATE / 1_000_000
            padTo(next)
            regionPts = ptsUs
            inputFrames = 0L
            format = spec
            resampler = if (spec.rate == SAMPLE_RATE) null else SpeechResampler(spec.rate, ::emit)
        }
        val filter = resampler
        while (pcm.hasRemaining()) {
            var mono = 0f
            repeat(spec.channels) { mono += spec.sample(pcm) }
            mono /= spec.channels
            if (filter == null) emit(mono) else filter.push(mono)
            inputFrames++
        }
    }

    fun finish() {
        resampler?.finish()
        flush()
    }

    private fun padTo(frame: Long) {
        if (frame <= written) return
        checkLimit(frame)
        flush()
        buffer.fill(0)
        while (written < frame) {
            val count = (frame - written).coerceAtMost(buffer.size / 2L).toInt()
            output.write(buffer, 0, count * 2)
            written += count
        }
    }

    private fun emit(sample: Float) {
        val frame = next++
        if (frame < written) return // codec overlap or audio before time zero
        checkLimit(frame + 1)
        val short = (sample * 32768).roundToInt().coerceIn(-32768, 32767)
        buffer[size++] = short.toByte()
        buffer[size++] = (short shr 8).toByte()
        written++
        hasAudio = true
        if (size == buffer.size) flush()
    }

    private fun flush() {
        if (size != 0) output.write(buffer, 0, size)
        size = 0
    }

    private fun checkLimit(frames: Long) {
        if (frames > MAX_LOCAL_SECONDS * SAMPLE_RATE.toLong()) throw OfflineFailure(OfflineIssue.TOO_LONG)
    }
}

/** Bounded streaming low-pass + rational resampler. Keeps speech-band energy without aliasing. */
internal class SpeechResampler(private val inputRate: Int, private val output: (Float) -> Unit) {
    private val half = 24
    private val phases = 128
    private val coefficients = Array(phases) { phase ->
        val cutoff = 0.47 * minOf(1.0, SAMPLE_RATE.toDouble() / inputRate)
        val kernel = FloatArray(half * 2) { tap ->
            val x = (tap - half + 1) - phase.toDouble() / phases
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val window = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)
            (sinc * window).toFloat()
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        kernel
    }
    private val ring = FloatArray(128)
    private var first = 0f
    private var tail = 0f
    private var count = 0L
    private var position = 0L

    fun push(sample: Float) {
        if (count == 0L) first = sample
        tail = sample
        ring[(count++ % ring.size).toInt()] = sample
        while (position / SAMPLE_RATE + half < count) emit()
    }

    fun finish() {
        while (position < count * SAMPLE_RATE) emit()
    }

    private fun emit() {
        val center = position / SAMPLE_RATE
        val phase = ((position % SAMPLE_RATE) * phases / SAMPLE_RATE).toInt()
        val kernel = coefficients[phase]
        var sum = 0f
        for (tap in kernel.indices) {
            val index = center + tap - half + 1
            val sample = when {
                index < 0 -> first
                index >= count -> tail
                else -> ring[(index % ring.size).toInt()]
            }
            sum += sample * kernel[tap]
        }
        output(sum)
        position += inputRate
    }
}
