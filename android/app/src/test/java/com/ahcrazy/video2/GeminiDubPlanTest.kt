package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_CUES
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SECONDS
import com.ahcrazy.video2.core.dubbing.GeminiMatch
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiPerformance
import com.ahcrazy.video2.core.dubbing.GeminiReaction
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.GeminiSpeechWord
import com.ahcrazy.video2.core.dubbing.GeminiTone
import com.ahcrazy.video2.core.dubbing.attributeGeminiLines
import com.ahcrazy.video2.core.dubbing.directGeminiCast
import com.ahcrazy.video2.core.dubbing.geminiPresets
import com.ahcrazy.video2.core.dubbing.geminiScenes
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
        val tooMany = runCatching { attributeGeminiLines(plan, words + word("ninth", 9.0)) }.exceptionOrNull() as DubFailure
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

    @Test fun boundsAreEnforcedBeforeSendingAnyAudio() {
        val limit = planDub(listOf(cue(1, 0.0)), GEMINI_MAX_SECONDS.toDouble())
        validateGeminiPlan(limit)
        val overTime = runCatching { validateGeminiPlan(limit.copy(frames = limit.frames + 1)) }.exceptionOrNull() as DubFailure
        assertEquals(DubIssue.GEMINI_SIZE, overTime.issue)
        val overLines = runCatching { validateGeminiPlan(limit.copy(lines = List(GEMINI_MAX_CUES + 1) { limit.lines.first() })) }
            .exceptionOrNull() as DubFailure
        assertEquals(DubIssue.GEMINI_SIZE, overLines.issue)
    }

    private fun cue(number: Int, start: Double, text: String = "أهلًا") = SubCue(number, start, start + 0.9, text)
    private fun word(label: String, start: Double, end: Double = start + 0.4) = GeminiSpeechWord(label, frame(start), frame(end))
    private fun frame(seconds: Double) = (seconds * DUB_SAMPLE_RATE).roundToLong()
}
