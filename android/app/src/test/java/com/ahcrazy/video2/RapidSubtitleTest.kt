package com.ahcrazy.video2

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.OfflinePreset
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.data.offline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RapidSubtitleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val speech = OfflineModel.MOONSHINE_EN
    private val firefox = OfflineModel.FIREFOX_EN_AR

    @Test fun optInRoutesUseManualEnglishAndNeverAssumeAutoOrArabic() {
        assertTrue(supportsLocalPair("en", "ar", speech, firefox))
        assertTrue(supportsLocalPair("en", "es", speech, OfflineModel.HYMT_LITE))
        assertTrue(supportsLocalPair("en", "ar", OfflineModel.WHISPER_MEDIUM_Q5, firefox))
        assertFalse(supportsLocalPair("auto", "ar", speech, firefox))
        assertFalse(supportsLocalPair("auto", "ar", OfflineModel.WHISPER_MEDIUM_Q5, firefox))
        assertFalse(supportsLocalPair("ar", "en", speech, OfflineModel.HYMT_LITE))
        assertFalse(supportsLocalPair("en", "fr", speech, firefox))
        assertTrue(supportsLocalPair("auto", "ar", OfflineModel.WHISPER_MEDIUM_Q5, OfflineModel.HYMT_LITE))
        assertFalse(speech.supportsFastSpeech) // Whisper beam switch doesn't affect Moonshine.
        assertEquals(28, firefox.minSdk)
    }

    @Test fun weightPartsArePinnedAndHaveUniqueSafNamesWithoutMovingLegacyWeights() {
        assertEquals(8, speech.assets.size)
        assertEquals(3, firefox.assets.size)
        assertEquals(142_300_974L, speech.bytes)
        assertEquals(35_565_070L, firefox.bytes)
        assertEquals(speech, OfflineModel.speechForJob(speech.id))
        assertEquals(firefox, OfflineModel.translatorForJob(firefox.id))
        val parts = OfflineModel.entries.flatMap { it.assets }
        assertEquals(parts.size, parts.map { it.backupName }.distinct().size)
        assertTrue(parts.all { it.spec.url.startsWith("https://") && it.spec.bytes > 0 && it.spec.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals("hymt2-1p8b-q4-dc5f44fcf1fa.gguf", OfflineModel.HYMT_LITE.assets.single().backupName)
    }

    @Test fun utteranceBoundsAreClampedAndNoWordTimestampsAreInvented() {
        assertEquals(SpeechSegment(0, 35, "hello"), timedSpeechLine(-0.1, 0.45, " hello ", 100))
        assertEquals(SpeechSegment(80, 100, "world"), timedSpeechLine(0.8, 1.0, "world", 100))
        assertNull(timedSpeechLine(Double.NaN, 0.5, "hello", 100))
        assertNull(timedSpeechLine(1.01, 0.5, "world", 100))
        assertNull(timedSpeechLine(0.4, 0.0, "hello", 100))
        assertNull(timedSpeechLine(0.8, 1.0, "  ", 100))
    }

    @Test fun directBatchesArePositionalAndOnlyCommittedWhenComplete() {
        val video = tmp.newFile().apply { writeText("fake") }
        val dir = tmp.newFolder()
        val calls = mutableListOf<List<String>>()
        val loads = mutableListOf<OfflineModel>()
        var decoded = 0
        var closed = 0
        var broken = true
        val pipeline = OfflinePipeline({ model -> loads += model; video }, object : LocalEngines {
            override fun speech(model: java.io.File, signal: CancellationSignal): LocalSpeech = error("Not Whisper")
            override fun speechVulkan(model: java.io.File, signal: CancellationSignal): LocalSpeech = error("Not Vulkan")
            override fun speechMoonshine(model: java.io.File, signal: CancellationSignal) = object : LocalSpeech {
                override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult {
                    assertEquals("en", language)
                    decoded++
                    return SpeechResult("en", (0 until 24).map { SpeechSegment(it * 100L, it * 100L + 80, "English $it") })
                }
                override fun close() { closed++ }
            }
            override fun translator(model: java.io.File, kind: OfflineModel, signal: CancellationSignal) = object : LocalTranslator {
                override fun translateBatch(lines: List<String>): List<String> {
                    assertEquals(firefox, kind)
                    calls += lines
                    if (broken && lines.first() == "English 20") return listOf("ناقص")
                    return lines.map { "نص " + it.substringAfterLast(' ') }
                }
                override fun close() { closed++ }
            }
        }, vulkanSupported = { true }, vulkanHeadroom = { true }, pcmDecoder = { _, pcm, _, _ ->
            RandomAccessFile(pcm, "rw").use { it.setLength(25L * SAMPLE_RATE * 2); it.writeShort(100) }
        })
        fun run(stats: OfflineStats = OfflineStats()) = pipeline.run(dir, video, SubLang.EN, SubLang.AR, speech, CancellationSignal(),
            translator = firefox, preset = OfflinePreset.VULKAN, stats = stats) { _, _, _, _ -> }
        val failure = assertThrows(OfflineFailure::class.java) { run() }
        assertEquals(OfflineIssue.TRANSLATION, failure.issue)
        val checkpoints = OfflinePipeline.translationCheckpoint(dir, SubLang.AR, firefox)
        assertTrue(hasCheckpoint(java.io.File(checkpoints, "0.json")))
        assertFalse(hasCheckpoint(java.io.File(checkpoints, "1.json")))
        assertFalse(hasCheckpoint(OfflinePipeline.translatedCuesFile(dir, "ar", firefox)))
        assertNotEquals(OfflinePipeline.translationCheckpoint(dir, SubLang.AR, OfflineModel.HYMT_LITE), checkpoints)
        broken = false
        val timings = OfflineStats()
        val output = run(timings)
        assertEquals(24, output.size)
        for ((i, cue) in output.withIndex()) {
            assertEquals(i.toDouble(), cue.startSec, 0.001)
            assertEquals(i + 0.8, cue.endSec, 0.001)
            assertEquals("English $i", cue.original)
            assertEquals("نص $i", cue.translated)
        }
        assertEquals(listOf(speech, firefox, firefox), loads)
        assertEquals(listOf(20, 4, 4), calls.map { it.size })
        assertEquals(calls[1], calls[2])
        assertEquals(1, decoded)
        assertEquals(3, closed)
        assertEquals(1, timings.report().reusedBatches)
        assertEquals(1, timings.report().generationCalls)
    }

    @Test fun cancelledFirefoxBatchDoesNotWriteOrReplaceSubtitles() {
        val video = tmp.newFile().apply { writeText("fake") }
        val original = tmp.newFolder()
        val dir = tmp.newFolder()
        val signal = CancellationSignal()
        val cue = com.ahcrazy.video2.core.model.SubCue(0, 0.7, 1.9, "Good morning")
        CueJson.write(java.io.File(original, "cues-original.json"), listOf(cue))
        atomicText(java.io.File(original, "offline-source.json"), org.json.JSONObject().put("path", video.absolutePath)
            .put("size", video.length()).put("mtime", video.lastModified()).toString())
        atomicText(java.io.File(original, "offline-language.txt"), "en")
        val pipeline = OfflinePipeline({ video }, object : LocalEngines {
            override fun speech(model: java.io.File, signal: CancellationSignal): LocalSpeech = error("Transcript exists")
            override fun translator(model: java.io.File, kind: OfflineModel, signal: CancellationSignal) = object : LocalTranslator {
                override fun translateBatch(lines: List<String>): List<String> {
                    signal.cancel()
                    return listOf("صباح الخير")
                }
                override fun close() = Unit
            }
        })
        pipeline.reuseTranscript(original, dir, video, signal)
        assertThrows(OperationCanceledException::class.java) {
            pipeline.run(dir, video, SubLang.EN, SubLang.AR, speech, signal, translator = firefox) { _, _, _, _ -> }
        }
        assertEquals(listOf(cue), CueJson.read(java.io.File(original, "cues-original.json")))
        assertTrue(OfflinePipeline.translationCheckpoint(dir, SubLang.AR, firefox).listFiles().orEmpty().isEmpty())
    }
}
