package com.ahcrazy.video2.data.dubbing

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubLine
import com.ahcrazy.video2.core.dubbing.DubPlan
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SECONDS
import com.ahcrazy.video2.core.dubbing.GeminiDirection
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiPerformance
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.GeminiTake
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import com.ahcrazy.video2.core.dubbing.attributeGeminiLines
import com.ahcrazy.video2.core.dubbing.directGeminiCast
import com.ahcrazy.video2.core.dubbing.geminiScenes
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.dubbing.take
import com.ahcrazy.video2.core.dubbing.validateGeminiPlan
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.data.offline.OfflineFailure
import com.ahcrazy.video2.data.offline.OfflineIssue
import com.ahcrazy.video2.data.offline.SAMPLE_RATE
import com.ahcrazy.video2.data.offline.decodeLocalPcmBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun loadGeminiDubConfig(settings: AppSettings): GeminiDubConfig = withContext(Dispatchers.IO) {
    try { GeminiDubConfig.from(settings.getKey("gemini")) ?: throw DubFailure(DubIssue.GEMINI_SETUP) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { throw DubFailure(DubIssue.GEMINI_SETUP) }
}

internal class GeminiDubMedia private constructor(val file: File, private val size: Long, private val modified: Long) {
    fun check() {
        if (!file.isFile || file.length() != size || file.lastModified() != modified) throw DubFailure(DubIssue.SOURCE_CHANGED)
    }

    companion object {
        fun from(video: SavedVideo, source: DubSource): GeminiDubMedia {
            val file = File(video.localPath)
            if (video.kind in setOf(MediaKind.HLS, MediaKind.DASH) || !file.isAbsolute || !file.isFile ||
                file.extension.lowercase() in setOf("m3u", "m3u8", "mpd")) throw DubFailure(DubIssue.GEMINI_LOCAL)
            validateGeminiPlan(source.plan)
            return GeminiDubMedia(file, file.length(), file.lastModified())
        }
    }
}

internal enum class GeminiStep { EXTRACT_AUDIO, ASSIGN_SPEAKERS, DIRECT_SCENES }
internal data class GeminiStage(val step: GeminiStep, val scene: Int = 0, val total: Int = 0)

/** In-memory, sheet-scoped audio memo. Same take for preview and export; no extra upload to play it again. */
private class GeminiTakeCache {
    private val takes = LinkedHashMap<GeminiTake, ByteArray>(16, 0.75f, true)
    private var bytes = 0
    operator fun get(take: GeminiTake) = takes[take]
    fun put(take: GeminiTake, wav: ByteArray) {
        val max = 8 * 1024 * 1024
        if (wav.size > max) return
        bytes -= takes.put(take, wav)?.size ?: 0
        bytes += wav.size
        val old = takes.values.iterator()
        while (bytes > max && old.hasNext()) { bytes -= old.next().size; old.remove() }
    }
}

internal class GeminiDubSession private constructor(private val source: DubSource, private val media: GeminiDubMedia,
                                                   val direction: GeminiDirection, private val cache: GeminiTakeCache) {
    internal constructor(source: DubSource, media: GeminiDubMedia, direction: GeminiDirection) :
        this(source, media, direction, GeminiTakeCache())

    fun withDirection(direction: GeminiDirection) = GeminiDubSession(source, media, direction, cache)

    fun checkSource(other: DubSource) {
        if (other != source || !source.stillExists()) throw DubFailure(DubIssue.SOURCE_CHANGED)
        media.check()
    }

    fun plan(options: GeminiOptions, preview: Boolean): DubPlan {
        val lines = source.plan.lines.filter { direction.take(it, options) != null }
        if (lines.isEmpty()) throw DubFailure(DubIssue.NO_SUBTITLES)
        val plan = source.plan.copy(lines = lines)
        return if (preview) direction.preview(plan) else plan
    }

    suspend fun synthesize(api: GeminiDubApi, options: GeminiOptions, line: DubLine, output: File): SpeechPcmHint {
        val ctx = currentCoroutineContext()
        ctx.ensureActive()
        val take = direction.take(line, options) ?: throw DubFailure(DubIssue.SYNTHESIS, line.number)
        val old = cache[take]
        val wav = old ?: api.synthesize(take, line.number)
        ctx.ensureActive()
        output.writeBytes(wav)
        if (old == null) {
            val pcm = try { readSpeechWav(output, SpeechPcmHint.GEMINI_PCM16, ctx::ensureActive) }
            catch (e: DubFailure) { throw DubFailure(e.issue, line.number) }
            if (pcm.samples.all { it == 0.toShort() }) throw DubFailure(DubIssue.SYNTHESIS, line.number)
            cache.put(take, wav)
        }
        return SpeechPcmHint.GEMINI_PCM16
    }
}

internal suspend fun analyzeGeminiDub(cacheDir: File, source: DubSource, media: GeminiDubMedia, api: GeminiDubApi,
                                      options: GeminiOptions, stage: suspend (GeminiStage) -> Unit): GeminiDubSession =
    withContext(Dispatchers.IO) {
        if (!options.consented) throw DubFailure(DubIssue.GEMINI_CONSENT)
        if (!source.stillExists()) throw DubFailure(DubIssue.SOURCE_CHANGED)
        media.check()
        val ctx = currentCoroutineContext()
        val pcm = File.createTempFile("kadr-gemini-", ".pcm", cacheDir)
        try {
            stage(GeminiStage(GeminiStep.EXTRACT_AUDIO))
            try { decodeLocalPcmBounded(media.file, pcm, GEMINI_MAX_SECONDS, ctx::ensureActive) }
            catch (e: CancellationException) { throw e }
            catch (e: OfflineFailure) { throw DubFailure(when (e.issue) {
                OfflineIssue.TOO_LONG -> DubIssue.GEMINI_SIZE
                OfflineIssue.STORAGE -> DubIssue.DISK
                else -> DubIssue.GEMINI_AUDIO
            }) }
            catch (_: Exception) { throw DubFailure(DubIssue.GEMINI_AUDIO) }
            media.check()
            GeminiDubSession(source, media, directGeminiAudio(pcm, source.plan, api, stage)).also { it.checkSource(source) }
        } finally { pcm.delete() }
    }

internal suspend fun directGeminiAudio(pcm: File, plan: DubPlan, api: GeminiDubApi,
                                      stage: suspend (GeminiStage) -> Unit): GeminiDirection {
    val bytes = pcm.length()
    if (bytes < 2 || bytes % 2 != 0L) throw DubFailure(DubIssue.GEMINI_AUDIO)
    if (bytes > GEMINI_MAX_SECONDS * SAMPLE_RATE * 2L) throw DubFailure(DubIssue.GEMINI_SIZE)
    val frames = bytes / 2
    val audio = GeminiAudioSlice(pcm, 0, frames)
    stage(GeminiStage(GeminiStep.ASSIGN_SPEAKERS))
    val attribution = attributeGeminiLines(plan, api.speakers(audio))
    val scenes = geminiScenes(plan, audio.durationFrames)
    val votes = mutableMapOf<Int, MutableList<GeminiRegister>>()
    val cues = mutableMapOf<Int, GeminiPerformance>()
    scenes.forEachIndexed { i, scene ->
        stage(GeminiStage(GeminiStep.DIRECT_SCENES, i + 1, scenes.size))
        val from = scene.start * SAMPLE_RATE / DUB_SAMPLE_RATE
        val to = ((scene.end * SAMPLE_RATE + DUB_SAMPLE_RATE - 1) / DUB_SAMPLE_RATE).coerceAtMost(frames)
        val notes = api.notes(GeminiAudioSlice(pcm, from, to), scene, attribution)
        notes.voices.forEach { (speaker, voice) -> votes.getOrPut(speaker) { mutableListOf() }.add(voice) }
        cues.putAll(notes.cues)
    }
    return directGeminiCast(attribution, votes, cues)
}
