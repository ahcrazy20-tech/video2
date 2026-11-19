package com.ahcrazy.video2.core.dubbing

internal const val GEMINI_MAX_SECONDS = 6 * 60
internal const val GEMINI_MAX_CUES = 600
internal const val GEMINI_MAX_SPEAKERS = 8
private const val GEMINI_MAX_TEXT = 1_200
private const val SCENE_FRAMES = 55L * DUB_SAMPLE_RATE
private const val SCENE_CUES = 32
private const val SCENE_CONTEXT = DUB_SAMPLE_RATE * 3L / 4

/** Presentation of a stock dubbing voice, not the identity or gender of a person. */
internal enum class GeminiRegister { MASCULINE, FEMININE, UNKNOWN }
internal data class GeminiPreset(val name: String, val register: GeminiRegister)

// Google AI's prebuilt voice catalog. Keep enough of each register for an all-male or all-female cast.
internal val geminiPresets = listOf(
    GeminiRegister.MASCULINE to listOf("Puck", "Charon", "Achird", "Orus", "Iapetus", "Algieba", "Fenrir", "Schedar"),
    GeminiRegister.FEMININE to listOf("Kore", "Aoede", "Sulafat", "Leda", "Despina", "Zephyr", "Gacrux", "Erinome")
).flatMap { (register, names) -> names.map { GeminiPreset(it, register) } }

internal enum class GeminiTone(val style: String) {
    NATURAL(""), QUESTIONING("natural questioning inflection"), WARM("warm and friendly"),
    HAPPY("cheerful and smiling"), EXCITED("excited, lively emphasis"), SURPRISED("audibly surprised"),
    SAD("subdued and sad"), ANGRY("tense and angry"), AFRAID("anxious and hesitant"),
    WHISPERED("whispered"), URGENT("urgent and brisk"), SARCASTIC("dry and sarcastic"),
}

internal enum class GeminiReaction(val tag: String, vararg val caption: String) {
    LAUGH("<laugh>", "laugh", "laughs", "laughing", "laughter", "ضحك", "ضحكة"),
    CHUCKLE("<chuckle>", "chuckle", "chuckles", "ضحك خفيف"),
    SIGH("<sigh>", "sigh", "sighs", "تنهد", "تنهيدة"),
    GASP("<gasp>", "gasp", "gasps", "شهقة"),
    COUGH("<cough>", "cough", "coughs", "سعال", "كحة"),
    SOB("<sob>", "sob", "sobs", "sobbing", "بكاء"),
    YAWN("<yawn>", "yawn", "yawns", "تثاؤب"),
}

internal data class GeminiPerformance(val tone: GeminiTone = GeminiTone.NATURAL,
                                      val reaction: GeminiReaction? = null, val reactionFirst: Boolean = false)
internal data class GeminiSpeechWord(val label: String, val start: Long, val end: Long)
internal data class GeminiMatch(val speaker: Int, val uncertain: Boolean)
internal data class GeminiAttribution(val matches: Map<Int, GeminiMatch>, val speakers: List<Int>)
internal data class GeminiCharacter(val id: Int, val register: GeminiRegister, val preset: GeminiPreset, val overridden: Boolean = false)
internal data class GeminiDirection(val cast: List<GeminiCharacter>, val matches: Map<Int, GeminiMatch>,
                                    val performance: Map<Int, GeminiPerformance>) {
    val uncertainCount: Int get() = matches.count { it.value.uncertain }

    fun voiceFor(cue: Int): GeminiPreset = cast.first { it.id == matches.getValue(cue).speaker }.preset

    fun withVoice(speaker: Int, voice: GeminiPreset): GeminiDirection = copy(cast = cast.map {
        if (it.id == speaker) it.copy(preset = voice, overridden = true) else it
    })

    fun withSpeaker(cue: Int, speaker: Int): GeminiDirection {
        if (cue !in matches || cast.none { it.id == speaker }) return this
        val previous = matches.getValue(cue)
        return copy(matches = matches + (cue to GeminiMatch(speaker, false)),
            // Delivery intended for a different person must not be copied onto this one.
            performance = if (previous.speaker == speaker) performance else performance - cue)
    }

    /** Prefer a short exchange to the first monologue. The full track keeps the original start frames. */
    fun preview(plan: DubPlan): DubPlan {
        val maxSpan = 15L * DUB_SAMPLE_RATE
        var best = emptyList<DubLine>()
        var bestScore = -1
        for ((index, first) in plan.lines.withIndex()) {
            if (first.end - first.start > maxSpan) continue
            val window = plan.lines.asSequence().drop(index).takeWhile { it.start < first.start + maxSpan }
                .filter { it.end <= first.start + maxSpan }.take(4).toList()
            val voices = window.mapNotNull { matches[it.number]?.takeUnless(GeminiMatch::uncertain)?.speaker }.distinct().size
            val acting = window.any { performance[it.number]?.let { p -> p.reaction != null || p.tone != GeminiTone.NATURAL } == true }
            val score = voices * 10 + (if (acting) 5 else 0) + window.size
            if (score > bestScore) { best = window; bestScore = score }
        }
        if (best.isEmpty()) return plan.preview()
        val from = best.first().start
        return DubPlan(best.map { it.copy(start = it.start - from, end = it.end - from) }, best.maxOf { it.end } - from, from)
    }
}

internal data class GeminiOptions(val allowAudio: Boolean, val allowText: Boolean, val egyptianArabic: Boolean,
                                  val reactions: Boolean = true) {
    val consented: Boolean get() = allowAudio && allowText
    val language: String? get() = if (egyptianArabic) "ar-EG" else null
}

/** The model may direct delivery, but cannot supply dialogue, choose endpoint/voice IDs, or change cue timing. */
internal data class GeminiTake(val voice: String, val text: String, val style: String, val language: String?)
private val subtitleFormatting = Regex("</?(?:b|i|u|s|font)(?:\\s[^<>]*)?>", RegexOption.IGNORE_CASE)
private val whitespace = Regex("\\s+")

private fun standaloneReaction(text: String): GeminiReaction? {
    val note = text.trim().let {
        if (it.startsWith('[') && it.endsWith(']') || it.startsWith('(') && it.endsWith(')'))
            it.substring(1, it.length - 1).trim().lowercase() else return null
    }
    return GeminiReaction.entries.firstOrNull { note in it.caption }
}

private fun speechText(text: String): String = text.replace(subtitleFormatting, "").replace(whitespace, " ").trim()
    // Only allow our own vocal tags and backchannel syntax in TTS. The saved SRT itself is untouched.
    .replace('<', '‹').replace('>', '›').replace('|', '｜')

internal fun validateGeminiPlan(plan: DubPlan) {
    if (plan.frames > GEMINI_MAX_SECONDS * DUB_SAMPLE_RATE.toLong() || plan.lines.size > GEMINI_MAX_CUES) {
        throw DubFailure(DubIssue.GEMINI_SIZE)
    }
    plan.lines.forEach {
        if (it.text.length > GEMINI_MAX_TEXT) throw DubFailure(DubIssue.CUE_TOO_LONG, it.number)
        if (speechText(it.text).isBlank()) throw DubFailure(DubIssue.NO_SUBTITLES, it.number)
    }
}

internal fun GeminiDirection.take(line: DubLine, options: GeminiOptions): GeminiTake? {
    if (!options.consented) throw DubFailure(DubIssue.GEMINI_CONSENT)
    val text = speechText(line.text)
    val caption = standaloneReaction(text)
    if (caption != null && !options.reactions) return null // Explicitly disabled, silent in this slot.
    val acting = performance[line.number] ?: GeminiPerformance()
    val tag = acting.reaction?.tag?.takeIf { options.reactions }
    val transcript = when {
        caption != null -> caption.tag
        tag == null -> text
        acting.reactionFirst -> "$tag $text"
        else -> "$text $tag"
    }
    return GeminiTake(voiceFor(line.number).name, transcript, acting.tone.style, options.language)
}

/** A single diarization response covers the whole clip so speaker IDs never get re-used across chunks. */
internal fun attributeGeminiLines(plan: DubPlan, words: List<GeminiSpeechWord>): GeminiAttribution {
    val tracks = words.sortedBy { it.start }.groupBy { it.label }.values.map { spans ->
        // Merge repeats/overlaps from one speaker so a repeated ASR word doesn't win the assignment.
        val merged = mutableListOf<LongRange>()
        for (word in spans) {
            val last = merged.lastOrNull()
            if (last != null && word.start <= last.last) merged[merged.lastIndex] = last.first..maxOf(last.last, word.end)
            else merged += word.start..word.end
        }
        merged
    }
    if (tracks.isEmpty()) throw DubFailure(DubIssue.GEMINI_ANALYSIS)
    if (tracks.size > GEMINI_MAX_SPEAKERS) throw DubFailure(DubIssue.GEMINI_CAST)
    val matches = plan.lines.associate { line ->
        val scores = tracks.mapIndexed { index, spans ->
            val overlap = spans.asSequence().dropWhile { it.last <= line.start }.takeWhile { it.first < line.end }
                .sumOf { minOf(line.end, it.last) - maxOf(line.start, it.first) }
            index + 1 to overlap
        }.sortedByDescending { it.second }
        val (speaker, best) = scores.first()
        val next = scores.getOrNull(1)?.second ?: 0
        line.number to if (best == 0L) null else GeminiMatch(speaker,
            uncertain = best < minOf(line.frames / 2L, DUB_SAMPLE_RATE / 8L) || next * 3 > best)
    }
    val used = matches.values.filterNotNull().groupingBy { it.speaker }.eachCount()
    // No timing match is usually an incompatible SRT, not evidence for a default speaker.
    val fallback = used.maxByOrNull { it.value }?.key ?: throw DubFailure(DubIssue.GEMINI_ANALYSIS)
    val displayIds = used.keys.sorted().withIndex().associate { (index, id) -> id to index + 1 }
    return GeminiAttribution(matches.mapValues { (_, original) ->
        val match = original ?: GeminiMatch(fallback, true)
        match.copy(speaker = displayIds.getValue(match.speaker))
    }, (1..used.size).toList())
}

internal fun directGeminiCast(attribution: GeminiAttribution, guesses: Map<Int, List<GeminiRegister>>,
                              performance: Map<Int, GeminiPerformance>): GeminiDirection {
    val registers = attribution.speakers.associateWith { speaker ->
        val votes = guesses[speaker].orEmpty()
        val male = votes.count { it == GeminiRegister.MASCULINE }
        val female = votes.count { it == GeminiRegister.FEMININE }
        when {
            male > female && male >= female * 2 -> GeminiRegister.MASCULINE
            female > male && female >= male * 2 -> GeminiRegister.FEMININE
            else -> GeminiRegister.UNKNOWN
        }
    }
    val used = mutableSetOf<String>()
    val cast = registers.entries.sortedBy { it.value == GeminiRegister.UNKNOWN }.map { (id, register) ->
        val pool = if (register == GeminiRegister.UNKNOWN) geminiPresets.sortedBy { it.name !in setOf("Achird", "Sulafat") }
                   else geminiPresets.filter { it.register == register }
        val voice = pool.first { used.add(it.name) }
        GeminiCharacter(id, register, voice)
    }.sortedBy { it.id }
    return GeminiDirection(cast, attribution.matches, performance)
}

internal data class GeminiScene(val start: Long, val end: Long, val cues: List<DubLine>)

/** Smaller listening windows keep local timestamps easy to follow. Their context may overlap, cue IDs never do. */
internal fun geminiScenes(plan: DubPlan, audioEnd: Long): List<GeminiScene> {
    val scenes = mutableListOf<GeminiScene>()
    var index = 0
    while (index < plan.lines.size) {
        val first = plan.lines[index]
        val start = (first.start - SCENE_CONTEXT).coerceAtLeast(0)
        if (first.start >= audioEnd) break
        val limit = first.start + SCENE_FRAMES
        val cues = mutableListOf(first)
        index++
        while (index < plan.lines.size && cues.size < SCENE_CUES) {
            val next = plan.lines[index]
            if (next.start >= audioEnd || next.end > limit || next.start >= limit) break
            cues.add(next)
            index++
        }
        val end = minOf(cues.maxOf { it.end }, limit) + SCENE_CONTEXT
        scenes += GeminiScene(start, minOf(end, audioEnd), cues)
    }
    return scenes
}
