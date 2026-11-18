package com.ahcrazy.video2.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ahcrazy.video2.core.media.MediaDownloadException
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Stream samples without transcoding or publishing a silent/partial file. The inputs stay private until this succeeds. */
internal suspend fun muxClearMp4(picture: File, sound: File, output: File, expectedSec: Double?) {
    val video = MediaExtractor()
    val audio = MediaExtractor()
    output.delete()
    try {
        val v = video.openTrack(picture, "video/avc")
        val a = audio.openTrack(sound, "audio/mp4a-latm")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val imageTrack = muxer.addTrack(v)
            val soundTrack = muxer.addTrack(a)
            muxer.start()
            val context = currentCoroutineContext()
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastVideo = -1L
            var lastAudio = -1L
            while (video.sampleTime >= 0 || audio.sampleTime >= 0) {
                context.ensureActive()
                val hasVideo = video.sampleTime >= 0
                val image = hasVideo && (audio.sampleTime < 0 || video.sampleTime <= audio.sampleTime)
                val reader = if (image) video else audio
                val flags = reader.sampleFlags
                if ((flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw ProtectedMedia(DRMKind.UNKNOWN_PROTECTED)
                buffer.clear()
                val size = reader.readSampleData(buffer, 0)
                if (size !in 1..buffer.capacity()) throw invalidTracks()
                val time = reader.sampleTime
                val sync = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                buffer.position(0)
                buffer.limit(size)
                info.set(0, size, time, sync)
                muxer.writeSampleData(if (image) imageTrack else soundTrack, buffer, info)
                if (image) lastVideo = maxOf(lastVideo, time) else lastAudio = maxOf(lastAudio, time)
                reader.advance()
            }
            val length = maxOf(lastVideo, lastAudio) / 1_000_000.0
            val tolerance = maxOf(2.0, length * .02)
            if (lastVideo < 0 || lastAudio < 0 || abs(lastVideo - lastAudio) > tolerance * 1_000_000 ||
                expectedSec != null && abs(length - expectedSec) > maxOf(4.0, expectedSec * .03)) throw invalidTracks()
            muxer.stop()
        } finally { runCatching { muxer.release() } }
    } catch (e: Exception) {
        output.delete()
        when (e) {
            is kotlinx.coroutines.CancellationException, is ProtectedMedia, is MediaDownloadException -> throw e
            else -> throw invalidTracks()
        }
    } finally {
        video.release()
        audio.release()
    }
}

private fun MediaExtractor.openTrack(input: File, mime: String): MediaFormat {
    setDataSource(input.absolutePath)
    if (drmInitData != null) throw ProtectedMedia(DRMKind.UNKNOWN_PROTECTED)
    val track = (0 until trackCount).firstOrNull { getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime } ?: throw invalidTracks()
    selectTrack(track)
    return getTrackFormat(track)
}

private fun invalidTracks() = MediaDownloadException("تعذّر دمج مساري الصورة والصوت. جرّب مصدرًا آخر.", MediaProblem.NOT_MEDIA)
