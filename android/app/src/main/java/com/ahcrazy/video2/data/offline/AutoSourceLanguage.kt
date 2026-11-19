package com.ahcrazy.video2.data.offline

import java.util.Locale

private val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*")

/** Whisper normally returns a bare code; accept portable spelling and region tags in checkpoints. */
internal fun detectedSourceCode(value: String): String? {
    val tag = value.trim().lowercase(Locale.ROOT)
    return tag.takeIf { LANGUAGE_TAG.matches(it) }?.substringBefore('-')?.substringBefore('_')
}

internal data class LanguageSlice(val start: Long, val end: Long)

/** Probe disjoint, later pieces of the same audio only after an unusable first detection. */
internal fun languageRescueSlices(anchor: Long, totalFrames: Long): List<LanguageSlice> {
    val span = (totalFrames - anchor).coerceIn(0L, 100L * SAMPLE_RATE)
    val skip = minOf(8L * SAMPLE_RATE, span / 4)
    val available = span - skip
    val count = (available / (4L * SAMPLE_RATE)).toInt().coerceAtMost(4)
    if (count < 2) return emptyList()
    val width = minOf(10L * SAMPLE_RATE, available / count)
    return List(count) { index ->
        val from = anchor + skip + (available - width) * index / (count - 1)
        LanguageSlice(from, from + width)
    }
}

/** Do not guess a supported language just because it was the only supported result in the sample. */
internal class LanguageEvidence {
    private val votes = mutableMapOf<String, MutableSet<String>>()
    private var valid = 0

    fun observe(result: SpeechResult, frames: Int) {
        val code = detectedSourceCode(result.language) ?: return
        val limit = frames / (SAMPLE_RATE / 100)
        val audible = result.segments.filter { it.startCs in 0L until limit.toLong() && it.endCs > it.startCs && it.text.isNotBlank() }
        val voiced = audible.sumOf { it.endCs.coerceAtMost(limit.toLong()) - it.startCs }
        val text = audible.joinToString(" ") { it.text.filter(Char::isLetter) }.lowercase(Locale.ROOT)
        if (voiced < 90 || text.count(Char::isLetter) < 10) return
        valid++
        votes.getOrPut(code) { mutableSetOf() } += text
    }

    fun supportedMajority(): String? = votes.entries.singleOrNull { (code, samples) ->
        code in hymtSourceLanguages && samples.size >= 2 && samples.size > valid / 2
    }?.key
}
