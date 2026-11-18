package com.ahcrazy.video2.data.offline

import com.ahcrazy.video2.core.model.SubCue
import java.util.Locale

// Official Hy-MT2-supported ISO codes also used by Whisper (plus legacy aliases).
internal val hymtSourceLanguages = setOf("zh", "en", "fr", "pt", "es", "ja", "tr", "ru", "ar", "ko", "th",
    "it", "de", "vi", "ms", "id", "tl", "hi", "pl", "cs", "nl", "km", "my", "fa", "gu", "ur", "te", "mr",
    "he", "iw", "bn", "ta", "uk", "bo", "kk", "mn", "ug", "yue")
internal const val SUBTITLE_DELIMITER = "[[V2_SPLIT]]"
private const val MAX_BATCH_CHARS = 720
private const val SCENE_GAP_SECONDS = 4.0
private const val CONTEXT_LINES = 2
private const val CONTEXT_CHARS = 200
private const val BACKGROUND = "[Background Information]"
private const val SOURCE_TEXT = "[Source Text]"
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

// Keep this partition identical for fresh, streaming, and pre-2.1 checkpoints.
internal fun planTranslation(cues: List<SubCue>): List<List<SubCue>> = buildList {
    var start = 0
    while (start < cues.size) {
        val batch = nextTranslationBatch(cues, start, final = true) ?: break
        add(batch)
        start += batch.size
    }
}

internal fun nextTranslationBatch(cues: List<SubCue>, start: Int, final: Boolean): List<SubCue>? {
    if (start >= cues.size) return null
    var end = start + 1
    var chars = cues[start].original.length
    while (end < cues.size && end - start < 6) {
        val cue = cues[end]
        if (chars + cue.original.length > MAX_BATCH_CHARS || cue.startSec - cues[end - 1].endSec > SCENE_GAP_SECONDS) break
        chars += cue.original.length
        end++
    }
    // Appending speech must not change the batch or its before/after context (including retries).
    return if (final || cues.size - end >= CONTEXT_LINES) cues.subList(start, end) else null
}

internal data class SubtitleContext(val before: List<String> = emptyList(), val after: List<String> = emptyList()) {
    fun background(): String {
        fun excerpt(lines: List<String>, beforeSource: Boolean): String =
            lines.joinToString(" ").replace(SUBTITLE_DELIMITER, " ").replace(WHITESPACE, " ").trim()
                .let { text ->
                    if (text.length <= CONTEXT_CHARS) text
                    else if (beforeSource) "…" + text.takeLast(CONTEXT_CHARS).dropWhile(Char::isLowSurrogate)
                    else text.take(CONTEXT_CHARS).dropLastWhile(Char::isHighSurrogate) + "…"
                }
        val preceding = excerpt(before, true)
        val following = excerpt(after, false)
        return listOfNotNull(preceding.takeIf { it.isNotEmpty() }?.let { "Previous lines: $it" },
            following.takeIf { it.isNotEmpty() }?.let { "Following lines: $it" }).joinToString("\n")
    }
}

/** Context from both sides of a batch, but never reach across a long pause into a different scene. */
internal fun contextForBatch(cues: List<SubCue>, start: Int, size: Int): SubtitleContext {
    val end = start + size
    val before = mutableListOf<String>()
    for (i in (start - 1) downTo (start - CONTEXT_LINES).coerceAtLeast(0)) {
        if (cues[i + 1].startSec - cues[i].endSec > SCENE_GAP_SECONDS) break
        before.add(cues[i].original)
    }
    val after = mutableListOf<String>()
    for (i in end until (end + CONTEXT_LINES).coerceAtMost(cues.size)) {
        if (cues[i].startSec - cues[i - 1].endSec > SCENE_GAP_SECONDS) break
        after.add(cues[i].original)
    }
    return SubtitleContext(before.asReversed(), after)
}

/** Validate delimiter alignment; smaller groups are retried locally, preserving their nearby context. */
internal fun translateLines(
    lines: List<String>, target: String, seed: Int, context: SubtitleContext = SubtitleContext(),
    generate: (String, Int) -> String?
): List<String> {
    require(lines.isNotEmpty())
    val background = context.background()
    val instructions = "Translate only the source text into natural $target subtitles. Preserve the spoken meaning and tone. " +
        "Output only the translation, without explanation."
    val prompt = buildString {
        if (background.isNotEmpty()) append("$BACKGROUND\n$background\n\n")
        append(instructions)
        if (background.isNotEmpty()) append(" Use the background to resolve meaning, but do not translate or output the background.")
        if (lines.size > 1) append(" Keep the same number and order of subtitle lines. " +
            "Preserve every $SUBTITLE_DELIMITER delimiter exactly, and do not move text between lines.")
        append("\n\n$SOURCE_TEXT\n")
        append(lines.joinToString("\n$SUBTITLE_DELIMITER\n"))
    }
    if (prompt.length <= 2_000 && (lines.size == 1 || lines.none { it.contains(SUBTITLE_DELIMITER) })) {
        val result = generate(prompt, seed)?.split(SUBTITLE_DELIMITER)?.map { it.replace(WHITESPACE, " ").trim() }
        if (result?.size == lines.size && result.all {
                it.isNotBlank() && '\uFFFD' !in it && "```" !in it && "<|" !in it && "<｜" !in it && "<think>" !in it && "<answer>" !in it &&
                    !it.contains(BACKGROUND, ignoreCase = true) && !it.contains(SOURCE_TEXT, ignoreCase = true)
            }) return result
    }
    if (lines.size == 1) {
        // Split oversized input on a word boundary, keeping both translations in this cue.
        val text = lines.single()
        if (text.length > MAX_BATCH_CHARS) {
            val space = text.lastIndexOf(' ', text.length / 2).takeIf { it > text.length / 4 }
            if (space != null) {
                val left = text.substring(0, space)
                val right = text.substring(space + 1)
                return listOf(translateLines(listOf(left), target, seed + 1,
                    context.copy(after = (listOf(right) + context.after).take(CONTEXT_LINES)), generate).single() + " " +
                    translateLines(listOf(right), target, seed + 2,
                        context.copy(before = (context.before + left).takeLast(CONTEXT_LINES)), generate).single())
            }
        }
        // If generation echoes the context or exhausts the budget, retry only this cue without it.
        if (background.isNotEmpty()) return translateLines(lines, target, seed + 1, generate = generate)
        throw OfflineFailure(OfflineIssue.TRANSLATION)
    }
    val middle = lines.size / 2
    val left = lines.take(middle)
    val right = lines.drop(middle)
    return translateLines(left, target, seed + 1, context.copy(after = (right + context.after).take(CONTEXT_LINES)), generate) +
        translateLines(right, target, seed + 2, context.copy(before = (context.before + left).takeLast(CONTEXT_LINES)), generate)
}
