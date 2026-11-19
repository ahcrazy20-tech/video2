package com.ahcrazy.video2.data.dubbing

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubLine
import com.ahcrazy.video2.core.dubbing.DubPlan
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.SpeechPcm
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import com.ahcrazy.video2.core.dubbing.TimedDubWav
import com.ahcrazy.video2.core.dubbing.planDub
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.model.DubbedInfo
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal data class DubSource(val plan: DubPlan, val language: String?, val translated: Boolean,
                              val file: File, val length: Long, val modified: Long) {
    fun stillExists() = file.isFile && file.length() == length && file.lastModified() == modified
}

internal suspend fun loadDubSource(video: SavedVideo): DubSource = withContext(Dispatchers.IO) {
    val translated = video.subtitleFiles["target"]?.let(::File)?.takeIf(File::isFile)
    val file = translated ?: video.subtitleFiles["orig"]?.let(::File)?.takeIf(File::isFile)
        ?: throw DubFailure(DubIssue.NO_SUBTITLES)
    val length = file.length()
    if (length > 8L * 1024 * 1024) throw DubFailure(DubIssue.TOO_LARGE)
    val modified = file.lastModified()
    val duration = video.durationSec?.takeIf { it.isFinite() && it > 0 } ?: readLocalDuration(video)
    DubSource(planDub(SrtCodec.parse(file), duration), video.subtitleTargetLang.takeIf { translated != null },
        translated != null, file, length, modified).also { if (!it.stillExists()) throw DubFailure(DubIssue.SOURCE_CHANGED) }
}

private fun readLocalDuration(video: SavedVideo): Double? {
    if (video.kind == MediaKind.HLS || video.kind == MediaKind.DASH) return null
    val file = File(video.localPath)
    if (!file.isAbsolute || !file.isFile || file.extension.lowercase() in setOf("m3u8", "mpd")) return null
    return runCatching {
        val media = MediaMetadataRetriever()
        try {
            media.setDataSource(file.path)
            media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.div(1000.0)
        } finally { media.release() }
    }.getOrNull()
}

private fun ShortArray.withoutEnginePadding(): ShortArray {
    val first = indexOfFirst { it != 0.toShort() }
    if (first < 0) return ShortArray(0)
    val last = indexOfLast { it != 0.toShort() }
    // Strip only zeros, and keep a few ms either side to avoid shaving off quiet phonemes.
    val from = (first - DUB_SAMPLE_RATE / 125).coerceAtLeast(0)
    val to = (last + 1 + DUB_SAMPLE_RATE / 125).coerceAtMost(size)
    return if (from == 0 && to == size) this else copyOfRange(from, to)
}

private fun sonic(samples: ShortArray, rate: Int, speed: Float, check: () -> Unit): ShortArray {
    if (rate == DUB_SAMPLE_RATE && speed == 1f) return samples
    val sonic = SonicAudioProcessor()
    sonic.setOutputSampleRateHz(DUB_SAMPLE_RATE)
    sonic.setSpeed(speed)
    sonic.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
    sonic.flush()
    val input = ByteBuffer.allocate(16_384).order(ByteOrder.nativeOrder())
    val buffer = ByteArray(16_384)
    val result = ByteArrayOutputStream(((samples.size * (DUB_SAMPLE_RATE.toDouble() / rate) / speed) * 2).toInt().coerceAtLeast(16))
    fun drain() {
        val raw = sonic.output
        while (raw.hasRemaining()) {
            val count = minOf(buffer.size, raw.remaining())
            raw.get(buffer, 0, count)
            result.write(buffer, 0, count)
        }
    }
    var start = 0
    try {
        while (start < samples.size) {
            check()
            input.clear()
            val count = minOf(input.capacity() / 2, samples.size - start)
            input.asShortBuffer().put(samples, start, count)
            input.limit(count * 2)
            sonic.queueInput(input)
            drain()
            start += count
        }
        sonic.queueEndOfStream()
        drain()
        val shorts = ByteBuffer.wrap(result.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    } finally { sonic.reset() }
}

private const val MAX_GAP_FRAMES = DUB_SAMPLE_RATE * 5L / 4L // about 1.25 seconds

private fun prepareSpeech(pcm: SpeechPcm, line: Int, check: () -> Unit): ShortArray =
    sonic(pcm.samples, pcm.rate, 1f, check).withoutEnginePadding().also {
        if (it.isEmpty()) throw DubFailure(DubIssue.SYNTHESIS, line)
    }

/** Natural speed when it fits, pitch-preserving speed-up when short on space, never cut a line. */
private fun tryFitSpeech(spoken: ShortArray, frames: Int, check: () -> Unit): ShortArray? {
    if (spoken.size <= frames) return spoken
    val factor = spoken.size / frames.toFloat()
    if (factor > 2.05f) return null
    for (speed in listOf((factor * 1.006f).coerceAtMost(2.05f), (factor * 1.035f).coerceAtMost(2.05f), 2.05f).distinct()) {
        val fitted = sonic(spoken, DUB_SAMPLE_RATE, speed, check).withoutEnginePadding()
        if (fitted.isNotEmpty() && fitted.size <= frames) return fitted
    }
    return null
}

internal fun fitSpeech(pcm: SpeechPcm, frames: Int, line: Int, check: () -> Unit = {}): ShortArray =
    tryFitSpeech(prepareSpeech(pcm, line, check), frames, check) ?: throw DubFailure(DubIssue.CUE_TOO_LONG, line)

internal data class DubRenderReport(val fittedInGap: Int, val skipped: List<Int>)

/** Render at exact starts. Borrow only unused gap, and allow a missing cue only if the caller explicitly agrees. */
internal suspend fun renderDub(plan: DubPlan, output: File, scratch: File,
                               synthesize: suspend (DubLine, File) -> SpeechPcmHint,
                               onUnfittable: suspend (Int) -> Boolean = { false },
                               progress: suspend (Int, Int) -> Unit = { _, _ -> }): DubRenderReport {
    val context = currentCoroutineContext()
    val check: () -> Unit = { context.ensureActive() }
    val skipped = mutableListOf<Int>()
    var fittedInGap = 0
    try {
        if (output.parentFile?.usableSpace?.let { it < plan.frames * 2 + 16L * 1024 * 1024 } == true) throw DubFailure(DubIssue.DISK)
        RandomAccessFile(output, "rw").use { sink ->
            val timeline = TimedDubWav(sink, plan.frames, check)
            var reservedEnd = 0L
            progress(0, plan.lines.size)
            plan.lines.forEachIndexed { i, line ->
                check()
                val hint = synthesize(line, scratch)
                val pcm = try { readSpeechWav(scratch, hint, check) }
                catch (e: DubFailure) { throw DubFailure(e.issue, line.number) }
                val speech = prepareSpeech(pcm, line.number, check)
                val next = plan.lines.getOrNull(i + 1)?.start ?: plan.frames
                val endWithGap = if (reservedEnd <= line.end) {
                    minOf(next, plan.frames, line.end + MAX_GAP_FRAMES).coerceAtLeast(line.end)
                } else line.end
                val fitted = tryFitSpeech(speech, line.frames, check)
                    ?: if (endWithGap > line.end) tryFitSpeech(speech, (endWithGap - line.start).toInt(), check) else null
                if (fitted == null) {
                    if (!onUnfittable(line.number)) throw DubFailure(DubIssue.CUE_TOO_LONG, line.number)
                    check()
                    skipped += line.number
                } else {
                    timeline.add(line.start, fitted)
                    if (line.start + fitted.size > line.end) fittedInGap++
                }
                reservedEnd = maxOf(reservedEnd, line.end, line.start + (fitted?.size ?: 0))
                progress(i + 1, plan.lines.size)
            }
            if (skipped.size == plan.lines.size) throw DubFailure(DubIssue.CUE_TOO_LONG, skipped.firstOrNull())
            timeline.finish()
        }
        return DubRenderReport(fittedInGap, skipped.toList())
    } finally { scratch.delete() }
}

private suspend fun renderWithEngine(context: Context, plan: DubPlan, voice: DubVoice, preferredEngine: String?, output: File,
                                     onUnfittable: suspend (Int) -> Boolean = { false },
                                     progress: suspend (Int, Int) -> Unit = { _, _ -> }) = AndroidDubEngine.use(context) { engine ->
    engine.select(voice, preferredEngine)
    val scratch = File.createTempFile("kadr-cue-", ".wav", context.cacheDir)
    renderDub(plan, output, scratch, engine::synthesize, onUnfittable, progress)
}

internal suspend fun auditionDub(context: Context, source: DubSource, voice: DubVoice, preferredEngine: String?, output: File) {
    renderWithEngine(context, source.plan.preview(), voice, preferredEngine, output)
}

internal suspend fun saveDub(context: Context, library: LibraryStore, video: SavedVideo, source: DubSource, voice: DubVoice,
                             preferredEngine: String?, onUnfittable: suspend (Int) -> Boolean,
                             progress: suspend (Int, Int) -> Unit): DubRenderReport {
    val info = DubbedInfo("android-tts", voice.id, voice.tag, System.currentTimeMillis(), source.plan.frames.toDouble() / DUB_SAMPLE_RATE)
    return publishDub(library, video, source, info) { staged ->
        renderWithEngine(context, source.plan, voice, preferredEngine, staged, onUnfittable, progress)
    }
}

/** The library only sees a fully built track; errors and cancellation leave its previous track in place. */
internal suspend fun <T> publishDub(library: LibraryStore, video: SavedVideo, source: DubSource, info: DubbedInfo,
                                    render: suspend (File) -> T): T = withContext(Dispatchers.IO) {
    if (!source.stillExists()) throw DubFailure(DubIssue.SOURCE_CHANGED)
    val dir = File(library.dubsDir, video.id)
    if (dir.canonicalFile.parentFile != library.dubsDir.canonicalFile || (!dir.isDirectory && !dir.mkdirs())) {
        throw DubFailure(DubIssue.DISK)
    }
    val staged = File.createTempFile("kadr-dub-", ".part", dir)
    val result = File(dir, "dub-${UUID.randomUUID()}.wav")
    var attached = false
    try {
        val report = render(staged)
        currentCoroutineContext().ensureActive()
        if (!source.stillExists()) throw DubFailure(DubIssue.SOURCE_CHANGED)
        if (!staged.renameTo(result)) throw DubFailure(DubIssue.DISK)
        val old = library.attachDub(video, result, info.copy(createdAt = System.currentTimeMillis()))
            ?: throw DubFailure(DubIssue.SOURCE_CHANGED)
        attached = true
        // Clean only a previous audio file owned by this video's dubbing folder, after the index is committed.
        old.dubbedAudioPath?.let { path -> runCatching {
            File(path).takeIf { it.canonicalFile.parentFile == dir.canonicalFile && it != result }?.delete()
        } }
        report
    } finally {
        staged.delete()
        if (!attached) result.delete()
    }
}
