package com.ahcrazy.video2.data.offline

import com.ahcrazy.video2.core.model.SubCue
import java.util.Locale

// Official Hy-MT2-supported ISO codes also used by Whisper (plus legacy aliases).
internal val hymtSourceLanguages = setOf("zh", "en", "fr", "pt", "es", "ja", "tr", "ru", "ar", "ko", "th",
    "it", "de", "vi", "ms", "id", "tl", "hi", "pl", "cs", "nl", "km", "my", "fa", "gu", "ur", "te", "mr",
    "he", "iw", "bn", "ta", "uk", "bo", "kk", "mn", "ug", "yue")
internal const val SUBTITLE_DELIMITER = "[[V2_SPLIT]]"
private const val MAX_BATCH_CHARS = 720
private val WORDS = Regex("\\S+")
private val WHITESPACE = Regex("\\s+")

internal data class SpeechWindow(val start: Long, val end: Long, val readStart: Long, val readEnd: Long) {
    companion object {
        const val CORE = 50L * SAMPLE_RATE
        const val OVERLAP = 4L * SAMPLE_RATE
        fun at(index: Int, frames: Long): SpeechWindow {
            val start = index * CORE
            val end = minOf(start + CORE, frames)
            return SpeechWindow(start, end, (start - OVERLAP).coerceAtLeast(0), minOf(end + OVERLAP, frames))
        }
    }
}

/** Use context across the seam, keeping complete crossing segments and de-duplicating overlaps. */
internal fun cuesInWindow(window: SpeechWindow, result: SpeechResult, previous: SubCue?, firstIndex: Int): List<SubCue> {
    var last = previous
    val cues = mutableListOf<SubCue>()
    val maxTime = (window.readEnd - window.readStart) / 160 // frames per Whisper centisecond
    for (segment in result.segments) {
        val start = window.readStart + segment.startCs.coerceIn(0, maxTime) * 160
        val end = window.readStart + segment.endCs.coerceIn(0, maxTime) * 160
        // Midpoint-only ownership can omit speech if two windows segment the same line differently.
        if (end <= start || end <= window.start || start >= window.end) continue
        var text = segment.text.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) continue
        val startSec = start.toDouble() / SAMPLE_RATE
        val endSec = end.toDouble() / SAMPLE_RATE
        val prev = last
        if (prev != null && prev.endSec > startSec) text = trimRepeatedPrefix(prev.original, text)
        val afterPrevious = maxOf(startSec, prev?.endSec ?: 0.0)
        if (endSec <= afterPrevious || text.isEmpty()) continue
        val cue = SubCue(firstIndex + cues.size, afterPrevious, endSec, original = text)
        cues += cue
        last = cue
    }
    return cues
}

private fun trimRepeatedPrefix(previous: String, current: String): String {
    fun normalized(word: String) = word.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val left = WORDS.findAll(previous).map { normalized(it.value) }.toList()
    val right = WORDS.findAll(current).toList()
    val normalizedRight = right.map { normalized(it.value) }
    for (count in minOf(16, left.size, right.size) downTo 1) {
        if (count == 1 && (left.size != 1 || right.size != 1)) break
        val prefix = normalizedRight.take(count)
        if (prefix.all(String::isNotEmpty) && left.takeLast(count) == prefix) {
            return current.substring(right[count - 1].range.last + 1).trimStart()
        }
    }
    return current
}

internal fun planTranslation(cues: List<SubCue>): List<List<SubCue>> {
    val groups = mutableListOf<List<SubCue>>()
    var current = mutableListOf<SubCue>()
    var chars = 0
    for (cue in cues) {
        val last = current.lastOrNull()
        if (last != null && (chars + cue.original.length > MAX_BATCH_CHARS || current.size >= 6 || cue.startSec - last.endSec > 4.0)) {
            groups.add(current)
            current = mutableListOf()
            chars = 0
        }
        current += cue
        chars += cue.original.length
    }
    if (current.isNotEmpty()) groups.add(current)
    return groups
}

/** Validate delimiter alignment; smaller groups are retried locally, never reassigning timestamps. */
internal fun translateLines(
    lines: List<String>, target: String, seed: Int, generate: (String, Int) -> String?
): List<String> {
    require(lines.isNotEmpty())
    val prompt = if (lines.size == 1) {
        "Translate the following text into $target. Note that you should only output the translated result without any additional explanation:\n\n${lines.single()}"
    } else {
        "Please accurately translate these connected subtitle lines into $target. Output only the translated result. " +
            "You must retain the exact same number of $SUBTITLE_DELIMITER delimiters, in order. Do not omit, escape, or translate the delimiter. " +
            "Translate each line in context but do not move text between lines.\n\n" + lines.joinToString("\n$SUBTITLE_DELIMITER\n")
    }
    if (prompt.length <= 2_000 && (lines.size == 1 || lines.none { it.contains(SUBTITLE_DELIMITER) })) {
        val result = generate(prompt, seed)?.split(SUBTITLE_DELIMITER)?.map { it.replace(WHITESPACE, " ").trim() }
        if (result?.size == lines.size && result.all { it.isNotBlank() && '\uFFFD' !in it && "```" !in it && "<|" !in it }) {
            return result
        }
    }
    if (lines.size == 1) {
        // A rare unusually long Whisper segment can exceed the token budget. Split at a word
        // boundary and keep both translations in the same cue, without cutting Unicode pairs.
        val text = lines.single()
        if (text.length > MAX_BATCH_CHARS) {
            val space = text.lastIndexOf(' ', text.length / 2).takeIf { it > text.length / 4 }
            if (space != null) return listOf(translateLines(listOf(text.substring(0, space)), target, seed + 1, generate).single() + " " +
                translateLines(listOf(text.substring(space + 1)), target, seed + 2, generate).single())
        }
        throw OfflineFailure(OfflineIssue.TRANSLATION)
    }
    val middle = lines.size / 2
    return translateLines(lines.take(middle), target, seed + 1, generate) +
        translateLines(lines.drop(middle), target, seed + 2, generate)
}
