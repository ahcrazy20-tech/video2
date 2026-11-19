package com.ahcrazy.video2.data.offline

private const val FRAMES_PER_CS = SAMPLE_RATE / 100
private const val LONG_GAP_CS = 250
private const val EXTRA_MARGIN_CS = 25 // In addition to the detector's padding.
private const val BRIDGE_CS = 80
private const val MIN_SAVED_FRAMES = 5 * SAMPLE_RATE
private const val NEAR_SILENT_PEAK = 32

/** Compacted PCM never leaves the scratch window; every interval has its position in the source. */
internal class GatedAudio internal constructor(
    val samples: ShortArray, val removedFrames: Int, private val spans: List<Span>
) {
    internal data class Span(val sourceStart: Int, val sourceEnd: Int, val packedStart: Int) {
        val length get() = sourceEnd - sourceStart
        val packedEnd get() = packedStart + length
    }

    fun restore(result: SpeechResult): SpeechResult? {
        val cues = mutableListOf<SpeechSegment>()
        val visited = BooleanArray(spans.size)
        var lastStart = -1L
        for (cue in result.segments) {
            if (cue.text.isBlank()) continue
            if ('\uFFFD' in cue.text || cue.startCs < 0 || cue.endCs <= cue.startCs || cue.endCs > samples.size / FRAMES_PER_CS) return null
            val matches = spans.indices.filter { i -> cue.startCs < spans[i].packedEnd && cue.endCs > spans[i].packedStart }
            // Never stretch a line over a removed pause, split its words by guesswork, or publish a
            // hallucination timed entirely inside the synthetic bridge. Re-decode the whole window.
            val index = matches.singleOrNull() ?: return null
            val span = spans[index]
            val start = span.sourceStart + (cue.startCs.coerceAtLeast(span.packedStart.toLong()) - span.packedStart)
            val end = span.sourceStart + (cue.endCs.coerceAtMost(span.packedEnd.toLong()) - span.packedStart)
            if (start < lastStart || end <= start) return null
            cues += cue.copy(startCs = start, endCs = end)
            visited[index] = true
            lastStart = start
        }
        // This is a structural guard, not a confidence score. A retained but untranscribed island
        // could be soft speech, so try the unfiltered window instead of silently dropping it.
        return if (spans.indices.all { visited[it] }) result.copy(segments = cues) else null
    }
}

/** Silero bounds arrive as sorted, padded centisecond pairs relative to this PCM window. */
internal fun planSilenceGate(samples: ShortArray, bounds: IntArray): GatedAudio? {
    val duration = samples.size / FRAMES_PER_CS
    if (samples.size < 8 * SAMPLE_RATE || bounds.size % 2 != 0 || bounds.size > 512) return null
    if (bounds.isEmpty()) {
        // A VAD miss is not proof of silence, especially with an accent, music or soft speech.
        return if (samples.all { it.toInt() in -NEAR_SILENT_PEAK..NEAR_SILENT_PEAK })
            GatedAudio(ShortArray(0), samples.size, emptyList()) else null
    }
    val source = mutableListOf<Pair<Int, Int>>()
    var rawEnd = 0
    var end = 0
    for (i in bounds.indices step 2) {
        val rawStart = bounds[i]
        val nextRawEnd = bounds[i + 1]
        if (rawStart < rawEnd || nextRawEnd <= rawStart || nextRawEnd > duration) return null
        rawEnd = nextRawEnd
        val start = (rawStart - EXTRA_MARGIN_CS).coerceAtLeast(0)
        val nextEnd = (rawEnd + EXTRA_MARGIN_CS).coerceAtMost(duration)
        val keepGap = start - end < LONG_GAP_CS || !quietGap(samples, end, start)
        if (source.isNotEmpty() && keepGap) {
            source[source.lastIndex] = source.last().first to nextEnd
        } else {
            source += (if (source.isEmpty() && keepGap) 0 else start) to nextEnd
        }
        end = nextEnd
    }
    if (duration - end < LONG_GAP_CS || !quietGap(samples, end, duration)) {
        source[source.lastIndex] = source.last().first to duration
    }
    var packedEnd = 0
    val spans = source.mapIndexed { i, (start, stop) ->
        GatedAudio.Span(start, stop, packedEnd + if (i == 0) 0 else BRIDGE_CS).also { packedEnd = it.packedEnd }
    }
    // Preserve the incomplete centisecond at the very end if the final range wasn't clipped.
    val tail = if (spans.last().sourceEnd == duration) samples.size % FRAMES_PER_CS else 0
    val packedFrames = packedEnd * FRAMES_PER_CS + tail
    val saved = samples.size - packedFrames
    if (saved < maxOf(MIN_SAVED_FRAMES, samples.size / 5)) return null
    val filtered = ShortArray(packedFrames)
    spans.forEachIndexed { i, span ->
        samples.copyInto(filtered, span.packedStart * FRAMES_PER_CS, span.sourceStart * FRAMES_PER_CS,
            span.sourceEnd * FRAMES_PER_CS + if (i == spans.lastIndex) tail else 0)
    }
    return GatedAudio(filtered, saved, spans)
}

/** Veto removal when even one short block contains appreciable energy. Music/noise may cost
 * throughput, but keeping such a gap is safer than treating a detector miss as certainty. */
private fun quietGap(samples: ShortArray, startCs: Int, endCs: Int): Boolean {
    val stop = endCs * FRAMES_PER_CS
    var i = startCs * FRAMES_PER_CS
    while (i < stop) {
        val limit = minOf(stop, i + 4 * FRAMES_PER_CS)
        var squares = 0L
        val count = limit - i
        while (i < limit) {
            val value = samples[i++].toInt()
            if (value !in -1_024..1_024) return false
            squares += value * value
        }
        if (squares > 128L * 128 * count) return false
    }
    return true
}
