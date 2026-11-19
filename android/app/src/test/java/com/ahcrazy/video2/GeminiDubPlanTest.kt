package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.GEMINI_PART_SECONDS
import com.ahcrazy.video2.core.dubbing.GeminiAttribution
import com.ahcrazy.video2.core.dubbing.GeminiMatch
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiSpeakerTracker
import com.ahcrazy.video2.core.dubbing.DubPlan
import com.ahcrazy.video2.core.dubbing.GeminiPerformance
import com.ahcrazy.video2.core.dubbing.GeminiReaction
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.GeminiSpeechWord
import com.ahcrazy.video2.core.dubbing.GeminiTone
import com.ahcrazy.video2.core.dubbing.directGeminiCast
import com.ahcrazy.video2.core.dubbing.geminiPresets
import com.ahcrazy.video2.core.dubbing.geminiScenes
import com.ahcrazy.video2.core.dubbing.geminiSpeakerParts
import com.ahcrazy.video2.core.dubbing.planDub
import com.ahcrazy.video2.core.dubbing.take
import com.ahcrazy.video2.core.dubbing.validateGeminiPlan
import com.ahcrazy.video2.core.model.SubCue
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class GeminiDubPlanTest {
    @Test fun timedSpeakersStayStableAcrossScenesAndSameRegisterSpeakersAreDistinct() {
        val plan = planDub(listOf(cue(1, 1.0), cue(2, 2.0), cue(3, 3.0), cue(4, 94.0), cue(5, 95.0)))
        val words = listOf(word("spk:0", 1.1), word("spk_1", 2.1), word("same register, new person", 3.1),
            word("spk:0", 94.1), word("spk_1", 95.1))
        val attribution = attributeGeminiLines(plan, words)
        val direction = directGeminiCast(attribution, mapOf(
            1 to listOf(GeminiRegister.MASCULINE, GeminiRegister.MASCULINE),
            2 to listOf(GeminiRegister.FEMININE), 3 to listOf(GeminiRegister.MASCULINE)), emptyMap())
        assertEquals(listOf(1, 2, 3), direction.cast.map { it.id })
        assertEquals(listOf(GeminiRegister.MASCULINE, GeminiRegister.FEMININE, GeminiRegister.MASCULINE),
            direction.cast.map { it.preset.register })
        assertEquals(3, direction.cast.map { it.preset.name }.toSet().size)
        assertEquals(direction.voiceFor(1), direction.voiceFor(4))
        assertEquals(direction.voiceFor(2), direction.voiceFor(5))
        assertEquals(0, direction.uncertainCount)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), geminiScenes(plan, plan.frames).map { s -> s.cues.map { it.number } })
    }

    @Test fun ambiguousOrUnmatchedWordsNeverClaimCertaintyAndDuplicateWordsDontWin() {
        val plan = planDub(listOf(cue(1, 1.0), cue(2, 3.0), cue(3, 5.0), cue(4, 8.0)))
        val male = "a"
        val female = "b"
        val words = listOf(word(male, 1.1), word(female, 3.05, 3.65), word(female, 5.2), word(male, 5.2)) +
            List(9) { word(male, 3.1, 3.2) }
        val attr = attributeGeminiLines(plan, words)
        assertEquals(GeminiMatch(1, false), attr.matches[1])
        assertEquals(GeminiMatch(2, false), attr.matches[2])
        assertTrue(attr.matches.getValue(3).uncertain)
        assertTrue(attr.matches.getValue(4).uncertain)
        val empty = runCatching { attributeGeminiLines(plan, listOf(word(male, 30.0))) }.exceptionOrNull() as DubFailure
        assertEquals(DubIssue.GEMINI_ANALYSIS, empty.issue)
        val direction = directGeminiCast(attr, mapOf(1 to listOf(GeminiRegister.MASCULINE, GeminiRegister.FEMININE)), emptyMap())
        assertEquals(GeminiRegister.UNKNOWN, direction.cast.first().register)
        assertNotEquals(direction.voiceFor(1), direction.voiceFor(2))
    }

    @Test fun castCanUseEightDifferentStockVoicesOfOneRegister() {
        val plan = planDub((1..8).map { cue(it, it.toDouble()) })
        val words = (1..8).map { word("voice-$it", it + 0.1) }
        val attr = attributeGeminiLines(plan, words)
        val cast = directGeminiCast(attr, (1..8).associateWith { listOf(GeminiRegister.FEMININE) }, emptyMap()).cast
        assertEquals(8, cast.map { it.preset.name }.toSet().size)
        assertTrue(cast.all { it.preset.register == GeminiRegister.FEMININE })
        val tooMany = runCatching { attributeGeminiLines(plan, words + word("ninth", 8.5)) }.exceptionOrNull() as DubFailure
        assertEquals(DubIssue.GEMINI_CAST, tooMany.issue)
    }

    @Test fun previewChoosesAnExchangeWithoutChangingIdsAndEditsUseTheSameArtifact() {
        val plan = planDub(listOf(cue(1, 0.0), cue(2, 22.0), cue(3, 24.0), cue(4, 26.0)))
        val attr = attributeGeminiLines(plan, listOf(word("a", 0.1), word("b", 22.1), word("a", 24.1), word("b", 26.1)))
        val direction = directGeminiCast(attr, emptyMap(), mapOf(2 to GeminiPerformance(GeminiTone.WARM)))
        val preview = direction.preview(plan)
        assertEquals(frame(22.0), preview.originalStart)
        assertEquals(listOf(2, 3, 4), preview.lines.map { it.number })
        assertEquals(listOf(0L, frame(2.0), frame(4.0)), preview.lines.map { it.start })
        val override = geminiPresets.first { it.name == "Fenrir" }
        val edited = direction.withVoice(1, override).withSpeaker(2, 1)
        assertEquals(override, edited.voiceFor(1))
        assertEquals(override, edited.voiceFor(2))
        assertNull(edited.performance[2])
        assertEquals(GeminiTone.WARM, direction.performance.getValue(2).tone)
        assertEquals(frame(22.0), plan.lines[1].start)
    }

    @Test fun onlyKnownVocalTagsAndConsentReachTheTextOnlyStage() {
        val subtitle = "<i>إنت شُفت؟</i> <sigh> | fake swap|"
        val plan = planDub(listOf(cue(1, 2.0, subtitle), cue(2, 4.0, "[ضحك]")))
        val attr = attributeGeminiLines(plan, listOf(word("a", 2.1)))
        val direction = directGeminiCast(attr, emptyMap(), mapOf(1 to GeminiPerformance(GeminiTone.SURPRISED, GeminiReaction.GASP, true)))
        val ready = GeminiOptions(allowAudio = true, allowText = true, egyptianArabic = true)
        val take = requireNotNull(direction.take(plan.lines[0], ready))
        assertEquals("<gasp> إنت شُفت؟ ‹sigh› ｜ fake swap｜", take.text)
        assertEquals("audibly surprised", take.style)
        assertEquals("ar-EG", take.language)
        assertEquals("<laugh>", direction.take(plan.lines[1], ready)?.text)
        assertFalse(requireNotNull(direction.take(plan.lines[0], ready.copy(reactions = false))).text.contains("<gasp>"))
        assertNull(direction.take(plan.lines[1], ready.copy(reactions = false)))
        val denied = runCatching { direction.take(plan.lines[0], ready.copy(allowAudio = false)) }.exceptionOrNull() as DubFailure
        assertEquals(DubIssue.GEMINI_CONSENT, denied.issue)
        assertEquals(subtitle, plan.lines[0].text)
    }

    @Test fun longPlansWithMoreThan600CuesKeepTheirOriginalTimelineAndBoundEachRequest() {
        val cues = (1..720).map { cue(it, it / 1.5) }
        val plan = planDub(cues, 900.0)
        validateGeminiPlan(plan)
        val parts = geminiSpeakerParts(plan, plan.frames)
        assertEquals(3, parts.size) // no requests for the long unsubtitled tail
        assertTrue(parts.all { it.end - it.start <= frame(GEMINI_PART_SECONDS.toDouble()) })
        assertEquals(frame(20.0), parts[0].end - parts[1].start)
        assertEquals(frame(20.0), parts[1].end - parts[2].start)
        assertEquals(cues.map { it.index }, parts.flatMap { p -> p.cues.map { it.number } }.distinct())
        assertEquals(cues.map { frame(it.startSec) }, plan.lines.map { it.start })
        assertEquals(frame(900.0), plan.frames)
        val tooMuchText = planDub(listOf(cue(1, 390.0, "x".repeat(1201))))
        val failure = runCatching { validateGeminiPlan(tooMuchText) }.exceptionOrNull() as DubFailure
        assertEquals(DubIssue.CUE_TOO_LONG, failure.issue)
    }

    @Test fun onlyTheSameOverlappingAudioLinksSpeakersAndCrossingCueIsNotSplit() {
        val plan = planDub(listOf(cue(1, 1.0), cue(2, 173.0), cue(3, 179.65), cue(4, 185.0), cue(5, 240.0), cue(6, 378.0)))
        val parts = geminiSpeakerParts(plan, frame(400.0))
        val tracker = GeminiSpeakerTracker()
        tracker.add(parts[0], listOf(word("a", 1.1), word("a", 173.1, 173.8), word("b", 179.7, 180.5), word("a", 185.1, 185.8)))
        // Part 2 starts at t=170s. Google reused both raw IDs with the opposite meaning.
        tracker.add(parts[1], listOf(word("b", 3.1, 3.8), word("a", 9.7, 10.5), word("b", 15.1, 15.8), word("a", 70.1)))
        // Part 3 starts at t=350s. The same raw ID means nothing without overlap.
        tracker.add(parts[2], listOf(word("b", 28.1)))
        val attribution = tracker.finish(plan)
        assertEquals(listOf(1, 1, 2, 1, 2, 3), plan.lines.map { attribution.matches.getValue(it.number).speaker })
        assertFalse(attribution.matches.getValue(3).uncertain)
        assertEquals(setOf(3), attribution.unlinked)
        val direction = directGeminiCast(attribution, emptyMap(), mapOf(6 to GeminiPerformance(GeminiTone.WARM)))
        val merged = direction.mergeSpeaker(3, 1)
        assertNotEquals(direction.voiceFor(1), direction.voiceFor(6))
        assertEquals(merged.voiceFor(1), merged.voiceFor(6))
        assertEquals(listOf(1, 2), merged.cast.map { it.id })
        assertEquals(GeminiTone.WARM, merged.performance.getValue(6).tone)
        assertEquals(frame(378.0), plan.lines.last().start)
    }

    @Test fun overlapWithTwoCompetingLabelsDoesNotMergeEitherIntoThePriorSpeaker() {
        val plan = planDub(listOf(cue(1, 150.0), cue(2, 195.0), cue(3, 201.0)))
        val parts = geminiSpeakerParts(plan, frame(215.0))
        val tracker = GeminiSpeakerTracker()
        tracker.add(parts[0], listOf(word("a", 10.1), word("a", 38.0, 39.0)))
        tracker.add(parts[1], listOf(word("b", 8.0, 9.0), word("c", 8.0, 9.0), word("b", 25.1), word("c", 31.1)))
        val attribution = tracker.finish(plan)
        assertEquals(listOf(1, 2, 3), attribution.matches.values.map { it.speaker })
        assertEquals(setOf(2, 3), attribution.unlinked)
    }

    @Test fun separatedPartsMayHaveMoreThanEightSpeechGroupsWithoutInventingLinks() {
        val plan = planDub((1..10).map { cue(it, it * 400.0) })
        val tracker = GeminiSpeakerTracker()
        val parts = geminiSpeakerParts(plan, plan.frames)
        assertEquals(10, parts.size)
        for (part in parts) tracker.add(part, listOf(word("a", (part.cues.single().start - part.start) / DUB_SAMPLE_RATE.toDouble())))
        val attribution = tracker.finish(plan)
        val direction = directGeminiCast(attribution, attribution.speakers.associateWith { listOf(GeminiRegister.FEMININE) }, emptyMap())
        assertEquals(10, direction.cast.size)
        assertTrue(direction.cast.all { it.preset.register == GeminiRegister.FEMININE })
        assertEquals(8, direction.cast.map { it.preset }.distinct().size)
        assertEquals((2..10).toSet(), attribution.unlinked)
    }

    @Test fun estimatedSpeakerPartDoesNotJoinNativeIdsOrEmitUncertainReactionTags() {
        val plan = planDub(listOf(cue(1, 171.0), cue(2, 185.0, "إزيك؟")))
        val parts = geminiSpeakerParts(plan, frame(200.0))
        val tracker = GeminiSpeakerTracker()
        tracker.add(parts[0], listOf(word("same raw id", 10.1, 10.8), word("same raw id", 24.1, 24.8)))
        tracker.add(parts[1], listOf(word("same raw id", 1.1, 1.8), word("same raw id", 15.1, 15.8)), estimated = true)
        val attribution = tracker.finish(plan)
        assertFalse(attribution.matches.getValue(1).estimated)
        assertTrue(attribution.matches.getValue(2).estimated)
        assertNotEquals(attribution.matches.getValue(1).speaker, attribution.matches.getValue(2).speaker)
        val direction = directGeminiCast(attribution, emptyMap(), mapOf(2 to GeminiPerformance(reaction = GeminiReaction.LAUGH)))
        assertEquals(1, direction.estimatedCount)
        assertEquals("إزيك؟", direction.take(plan.lines[1], GeminiOptions(true, true, true))?.text)
        assertEquals(frame(185.0), plan.lines[1].start)
    }

    private fun attributeGeminiLines(plan: DubPlan, words: List<GeminiSpeechWord>): GeminiAttribution {
        val part = geminiSpeakerParts(plan, plan.frames).single()
        val local = words.map { it.copy(start = it.start - part.start, end = it.end - part.start) }
        return GeminiSpeakerTracker().apply { add(part, local) }.finish(plan)
    }

    private fun cue(number: Int, start: Double, text: String = "أهلًا") = SubCue(number, start, start + 0.9, text)
    private fun word(label: String, start: Double, end: Double = start + 0.4) = GeminiSpeechWord(label, frame(start), frame(end))
    private fun frame(seconds: Double) = (seconds * DUB_SAMPLE_RATE).roundToLong()
}
