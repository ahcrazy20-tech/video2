package com.ahcrazy.video2.core.dubbing

import com.ahcrazy.video2.core.model.SubCue
import java.util.Locale
import kotlin.math.roundToLong

internal const val DUB_SAMPLE_RATE = 24_000
internal const val MAX_DUB_SECONDS = 12 * 60 * 60
internal const val MAX_DUB_FRAMES = MAX_DUB_SECONDS * DUB_SAMPLE_RATE.toLong()

internal enum class DubIssue { NO_SUBTITLES, TIMING, TOO_LARGE, NO_ENGINE, VOICE_CHANGED, NEEDS_DOWNLOAD, NEEDS_NETWORK,
    SYNTHESIS, CUE_TOO_LONG, AUDIO_FORMAT, DISK, SOURCE_CHANGED, AZURE_SETUP, AZURE_AUTH, AZURE_LIMIT, AZURE_SERVICE, AZURE_NETWORK,
    GEMINI_SETUP, GEMINI_KEY_FORMAT, GEMINI_KEY_STORAGE, GEMINI_KEY_REJECTED,
    GEMINI_CONSENT, GEMINI_LOCAL, GEMINI_SIZE, GEMINI_AUDIO, GEMINI_CAST, GEMINI_ANALYSIS,
    GEMINI_AUTH, GEMINI_PROJECT, GEMINI_LIMIT, GEMINI_MODEL, GEMINI_SERVICE, GEMINI_NETWORK, GEMINI_BLOCKED }
internal class DubFailure(val issue: DubIssue, val line: Int? = null) : Exception(issue.name)

internal data class DubLine(val number: Int, val start: Long, val end: Long, val text: String) {
    val frames: Int get() = (end - start).toInt()
}

internal data class DubPlan(val lines: List<DubLine>, val frames: Long, val originalStart: Long = 0L) {
    /** Short, timed, real subtitle audio. Avoid synthesizing a whole scene just to audition a voice. */
    fun preview(): DubPlan {
        val limit = 15L * DUB_SAMPLE_RATE
        val first = lines.firstOrNull { it.end - it.start <= limit } ?: lines.first()
        val from = first.start
        val selection = lines.asSequence().filter { it.start >= from && it.end <= from + limit }.take(4).toList()
            .ifEmpty {
                // If every cue is unusually long, audition a short excerpt instead of the entire line.
                val excerpt = first.text.take(120).let { if (it.length < first.text.length) it.substringBeforeLast(' ', it) else it }
                listOf(first.copy(end = from + limit, text = excerpt))
            }
        return DubPlan(selection.map { it.copy(start = it.start - from, end = it.end - from) },
            selection.maxOf { it.end } - from, originalStart = from)
    }
}

internal fun planDub(cues: List<SubCue>, videoDurationSec: Double? = null): DubPlan {
    if (cues.size > 25_000) throw DubFailure(DubIssue.TOO_LARGE)
    val lines = cues.mapIndexedNotNull { index, cue ->
        val text = cue.original.trim()
        if (text.isBlank()) return@mapIndexedNotNull null
        if (!cue.startSec.isFinite() || !cue.endSec.isFinite() || cue.startSec < 0 || cue.endSec <= cue.startSec) {
            throw DubFailure(DubIssue.TIMING, index + 1)
        }
        if (cue.endSec > MAX_DUB_SECONDS) throw DubFailure(DubIssue.TOO_LARGE)
        val start = (cue.startSec * DUB_SAMPLE_RATE).roundToLong()
        val end = (cue.endSec * DUB_SAMPLE_RATE).roundToLong()
        if (end <= start) throw DubFailure(DubIssue.TIMING, index + 1)
        DubLine(index + 1, start, end, text)
    }.sortedBy { it.start }
    if (lines.isEmpty()) throw DubFailure(DubIssue.NO_SUBTITLES)
    val duration = videoDurationSec?.takeIf { it.isFinite() && it > 0 } ?: 0.0
    if (duration > MAX_DUB_SECONDS) throw DubFailure(DubIssue.TOO_LARGE)
    return DubPlan(lines, maxOf(lines.maxOf { it.end }, (duration * DUB_SAMPLE_RATE).roundToLong()))
}

internal enum class DubProvider(val id: String) { ANDROID("android-tts"), AZURE("azure-neural"), GEMINI("gemini-scene") }

internal data class DubVoice(val id: String, val tag: String, val network: Boolean, val needsDownload: Boolean,
                            val quality: Int, val latency: Int, val provider: DubProvider = DubProvider.ANDROID,
                            val label: String? = null, val labelAr: String? = null) {
    val language: String get() = Locale.forLanguageTag(tag).language
}

internal data class DubVoices(
    val voices: List<DubVoice>, val defaultId: String?, val preferredEngine: String? = null, val engineLabel: String? = null,
) {
    val languages: List<String> get() = voices.map { it.language }.distinct().sorted()
    fun dialects(language: String): List<String> = voices.filter { it.language == language }.map { it.tag }.distinct().sorted()

    fun forDialect(tag: String): List<DubVoice> = voices.filter { it.tag == tag }
        .sortedWith(compareBy<DubVoice> { it.needsDownload }.thenBy { it.network }
            .thenBy { it.id != defaultId }.thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.id })

    /** Don't silently switch languages. Prefer a usable same-language dialect over a missing voice. */
    fun initialDialect(preferred: String): String {
        val tag = Locale.forLanguageTag(preferred.replace('_', '-'))
        if (tag.language.isEmpty()) return ""
        val matching = voices.filter { it.language == tag.language }
        if (matching.isEmpty()) return ""
        val best = matching.sortedWith(compareBy<DubVoice> { it.needsDownload }.thenBy { it.network }
            .thenBy { it.tag != tag.toLanguageTag() }.thenBy { it.id != defaultId }.thenByDescending { it.quality }.thenBy { it.tag })
        return best.first().tag
    }

    fun resolve(dialect: String, selectedId: String?, allowNetwork: Boolean): DubVoice? =
        forDialect(dialect).firstOrNull {
            (selectedId == null || it.id == selectedId) && !it.needsDownload && (allowNetwork || !it.network)
        }
}
