package com.ahcrazy.video2.core.dubbing

// At 16 kHz PCM16 with base64, a full part is < 9 MB even with both context tails.
private const val PART_CORE = 180L * DUB_SAMPLE_RATE
private const val PART_CONTEXT = 10L * DUB_SAMPLE_RATE
internal const val GEMINI_PART_SECONDS = 200
internal const val GEMINI_PART_SPEAKERS = 8

internal data class GeminiSpeakerPart(val start: Long, val end: Long, val coreStart: Long, val coreEnd: Long,
                                      val cues: List<DubLine>)

// Very long subtitles still speak at their original time. Judge their speaker from a bounded listening window.
private fun DubLine.evidenceEnd() = minOf(end, start + GEMINI_SCENE_FRAMES)

/** Only request parts that can contribute to a cue. Adjacent cores share 20 seconds of original audio. */
internal fun geminiSpeakerParts(plan: DubPlan, audioEnd: Long): List<GeminiSpeakerPart> {
    require(audioEnd in 1..MAX_DUB_FRAMES)
    val groups = sortedMapOf<Int, MutableList<DubLine>>()
    for (line in plan.lines) {
        if (line.start >= audioEnd) break
        val last = minOf(line.evidenceEnd(), audioEnd) - 1
        for (id in (line.start / PART_CORE).toInt()..(last / PART_CORE).toInt()) {
            groups.getOrPut(id) { mutableListOf() }.add(line)
        }
    }
    if (groups.isEmpty()) return emptyList()
    val from = (groups.getValue(groups.firstKey()).first().start - PART_CONTEXT).coerceAtLeast(0)
    val to = minOf(audioEnd, groups.getValue(groups.lastKey()).maxOf { it.evidenceEnd() } + PART_CONTEXT)
    return groups.map { (id, lines) ->
        val start = id * PART_CORE
        val end = minOf(start + PART_CORE, audioEnd)
        GeminiSpeakerPart(maxOf(from, start - PART_CONTEXT), minOf(to, end + PART_CONTEXT), start, end, lines)
    }
}

private data class Span(val start: Long, val end: Long) // half-open, on the original media timeline
private data class Track(val id: Int, val spans: List<Span>)

private fun List<Span>.firstEndingAfter(frame: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].end <= frame) low = mid + 1 else high = mid
    }
    return low
}

private fun List<Span>.speechIn(start: Long, end: Long): Long {
    var duration = 0L
    for (i in firstEndingAfter(start) until size) {
        val span = this[i]
        if (span.start >= end) break
        duration += minOf(span.end, end) - maxOf(span.start, start)
    }
    return duration
}

private fun commonSpeech(a: List<Span>, b: List<Span>, start: Long, end: Long): Long {
    var i = a.firstEndingAfter(start)
    var j = b.firstEndingAfter(start)
    var duration = 0L
    while (i < a.size && j < b.size) {
        val x = a[i]; val y = b[j]
        val from = maxOf(start, x.start, y.start)
        if (from >= end) break
        duration += (minOf(end, x.end, y.end) - from).coerceAtLeast(0)
        if (x.end < y.end) i++ else j++
    }
    return duration
}

/** Match mutually unambiguous activity on the *same* audio. Raw diarization labels never cross a response boundary. */
private fun links(before: List<Track>, now: List<List<Span>>, start: Long, end: Long): Map<Int, Int> {
    if (start >= end || before.isEmpty() || now.isEmpty()) return emptyMap()
    val oldTimes = before.map { it.spans.speechIn(start, end) }
    val newTimes = now.map { it.speechIn(start, end) }
    val scores = now.map { spans -> before.map { commonSpeech(it.spans, spans, start, end) } }
    fun winner(row: List<Long>): Int? {
        val best = row.indices.maxBy { row[it] }
        if (row[best] < DUB_SAMPLE_RATE / 2L || row.indices.any { it != best && row[it] * 2 >= row[best] }) return null
        return best
    }
    return buildMap {
        scores.forEachIndexed { index, row ->
            val old = winner(row) ?: return@forEachIndexed
            val shared = row[old]
            if (shared * 3 < 2 * maxOf(oldTimes[old], newTimes[index])) return@forEachIndexed
            if (winner(scores.map { it[old] }) == index) put(index, before[old].id)
        }
    }
}

/** Discard response-local tracks as we go; retain only one adjacent part and bounded cue scores. */
internal class GeminiSpeakerTracker {
    private var previous = emptyList<Track>()
    private var previousEnd = 0L
    private var previousEstimated = false
    private var nextId = 1
    private val unlinked = mutableSetOf<Int>()
    private val estimatedIds = mutableSetOf<Int>()
    private val scores = mutableMapOf<Int, MutableMap<Int, Long>>()

    fun add(part: GeminiSpeakerPart, words: List<GeminiSpeechWord>, estimated: Boolean = false, check: () -> Unit = {}) {
        val length = part.end - part.start
        val groups = words.asSequence().filter { it.start >= 0 && it.end > it.start && it.start < length }
            .sortedBy { it.start }.groupBy { it.label }.values
        if (groups.size > GEMINI_PART_SPEAKERS) throw DubFailure(DubIssue.GEMINI_CAST)
        val spans = groups.map { group ->
            buildList {
                for (word in group) {
                    val start = part.start + word.start
                    val end = part.start + minOf(word.end, length)
                    val last: Span? = lastOrNull()
                    // Merge duplicate/overlapping words from one track before scoring.
                    if (last != null && start <= last.end) set(lastIndex, Span(last.start, maxOf(last.end, end)))
                    else add(Span(start, end))
                }
            }
        }
        // Coarse JSON spans aren't evidence for an acoustic identity match across separate requests.
        val inherited = if (estimated || previousEstimated) emptyMap() else links(previous, spans, part.start, minOf(previousEnd, part.end))
        val pastFirstSpeech = nextId > 1
        val tracks = spans.mapIndexed { i, rows ->
            val id = inherited[i] ?: (nextId++).also { if (pastFirstSpeech) unlinked += it }
            if (estimated) estimatedIds += id
            Track(id, rows)
        }
        for (cue in part.cues) {
            check()
            val start = maxOf(cue.start, part.coreStart, part.start)
            val end = minOf(cue.evidenceEnd(), part.coreEnd, part.end)
            // The shared tails are for linking only. Every original PCM frame can vote at most once per speaker.
            if (start >= end) continue
            for (track in tracks) {
                val overlap = track.spans.speechIn(start, end)
                if (overlap == 0L) continue
                val votes = scores.getOrPut(cue.number) { mutableMapOf() }
                votes[track.id] = (votes[track.id] ?: 0L) + overlap
            }
        }
        previous = tracks
        previousEnd = part.end
        previousEstimated = estimated
    }

    fun finish(plan: DubPlan): GeminiAttribution {
        val matches = plan.lines.map { cue ->
            val sorted = scores[cue.number]?.entries?.sortedWith(compareBy<Map.Entry<Int, Long>> { it.key in estimatedIds }
                .thenByDescending { it.value }).orEmpty()
            val winner = sorted.firstOrNull() ?: return@map null
            GeminiMatch(winner.key, winner.value < minOf(cue.frames / 2L, DUB_SAMPLE_RATE / 8L) ||
                (sorted.getOrNull(1)?.value ?: 0L) * 3 > winner.value, estimated = winner.key in estimatedIds)
        }
        val used = matches.filterNotNull().map { it.speaker }.distinct().sorted()
        if (used.isEmpty()) throw DubFailure(DubIssue.GEMINI_ANALYSIS)
        val display = used.withIndex().associate { (index, id) -> id to index + 1 }
        // With no timed evidence, use a nearby match instead of borrowing the global lead actor's voice.
        var old = -1
        var next = 0
        val resolved = plan.lines.indices.associate { i ->
            val match = matches[i] ?: run {
                if (next <= i) {
                    next = i + 1
                    while (next < matches.size && matches[next] == null) next++
                }
                val nearest = if (old < 0 || next < matches.size &&
                    plan.lines[next].start - plan.lines[i].start < plan.lines[i].start - plan.lines[old].start) next else old
                requireNotNull(matches[nearest]).copy(uncertain = true)
            }
            if (matches[i] != null) old = i
            plan.lines[i].number to match.copy(speaker = display.getValue(match.speaker))
        }
        return GeminiAttribution(resolved, display.values.toList(), unlinked.mapNotNull(display::get).toSet())
    }
}
