package com.ahcrazy.video2

import android.app.Application
import android.os.CancellationSignal
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.data.offline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineLanguageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun wrongFirstAutoDetectionIsCheckedAgainstLaterSpeechBeforeSaving() {
        val audio = tmp.newFile().apply { writeText("test media") }
        val job = tmp.newFolder()
        val calls = mutableListOf<Pair<String, Int>>()
        var samples = 0
        var fullEnglish = 0
        val pipeline = pipeline { pcm, language ->
            calls += language to pcm.size
            when {
                language == "auto" && pcm.size > 10 * SAMPLE_RATE -> speech("sv", 4_00, "Should not be saved")
                language == "auto" -> speech("en-US", 1_00, listOf("The person is speaking English", "Please come to the kitchen now",
                    "We are ready to get started", "Thank you for waiting in the hall")[samples++])
                else -> {
                    assertEquals("en", language)
                    speech("en", 5_00, "Verified English window ${++fullEnglish}")
                }
            }
        }
        val result = pipeline.run(job, audio, SubLang.AUTO, SubLang.EN, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal()) { _, _, _, _ -> }
        assertEquals(4, samples)
        assertEquals(2, fullEnglish)
        assertEquals(listOf("Verified English window 1", "Verified English window 2"), result.map { it.original })
        assertEquals(result.map { it.original }, result.map { it.translated })
        assertEquals(listOf(5.0, 51.0), result.map { it.startSec })
        assertEquals("en", readCheckpoint(File(job, "offline-language.txt")))
        assertEquals("auto", calls.first().first)
        assertEquals("en", calls.last().first)
    }

    @Test fun unsupportedIsNotRelabeledWhenLaterSamplesDisagreeAndCorrectionKeepsOldCheckpoint() {
        val audio = tmp.newFile().apply { writeText("test media") }
        val job = tmp.newFolder()
        var samples = 0
        val pipeline = pipeline(translate = { prompt, _ ->
            assertTrue(prompt.contains("this is the actual transcript"))
            "ده التفريغ الصحيح"
        }) { pcm, language ->
            when {
                language == "auto" && pcm.size > 10 * SAMPLE_RATE -> speech("sv", 4_00, "this is the actual transcript")
                language == "auto" -> {
                    val votes = arrayOf("en" to "An independent English sample", "ja" to "日本語の別の発言についての文章",
                        "sv" to "En längre oberoende mening", "en" to "Another piece of speech in English")
                    val (code, text) = votes[samples++]
                    speech(code, 1_00, text)
                }
                else -> { assertEquals("sv", language); SpeechResult("sv", emptyList()) }
            }
        }
        val stopped = assertThrows(OfflineFailure::class.java) {
            pipeline.run(job, audio, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal()) { _, _, _, _ -> }
        }
        assertEquals(OfflineIssue.LANGUAGE, stopped.issue)
        val raw = File(job, "cues-original.json")
        val oldBytes = raw.readBytes()
        val corrected = tmp.newFolder()
        val noSpeech = pipeline(translate = { _, _ -> "ده التفريغ الصحيح" }) { _, _ -> error("Transcript must be reused") }
        noSpeech.reuseTranscript(job, corrected, audio, CancellationSignal(), SubLang.EN)
        val fixed = noSpeech.run(corrected, audio, SubLang.EN, SubLang.AR, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal()) { _, _, _, _ -> }
        assertEquals(1, fixed.size)
        assertEquals("this is the actual transcript", fixed.single().original)
        assertEquals("ده التفريغ الصحيح", fixed.single().translated)
        assertEquals(4.0, fixed.single().startSec, 0.0)
        assertEquals("sv", readCheckpoint(File(job, "offline-language.txt")))
        assertEquals("en", readCheckpoint(File(corrected, "offline-language.txt")))
        assertArrayEquals(oldBytes, raw.readBytes())
    }

    @Test fun correctAutomaticOrManualSourceDoesNotAddExtraProbes() {
        for (source in listOf(SubLang.AUTO, SubLang.EN)) {
            var calls = 0
            val pipeline = pipeline { pcm, language ->
                assertTrue(pcm.size > 10 * SAMPLE_RATE)
                assertEquals(if (calls++ == 0) source.code else "en", language)
                speech("en", 5_00, "This is a clear source sample")
            }
            val audio = tmp.newFile().apply { writeText("test media") }
            pipeline.run(tmp.newFolder(), audio, source, SubLang.EN, OfflineModel.WHISPER_MEDIUM_Q5, CancellationSignal()) { _, _, _, _ -> }
            assertEquals(2, calls)
        }
    }

    @Test fun repeatedIntroHallucinationCannotCorroborateItself() {
        val evidence = LanguageEvidence()
        repeat(4) { evidence.observe(speech("en", 100, "Thank you for watching"), 8 * SAMPLE_RATE) }
        assertNull(evidence.supportedMajority())
        assertEquals("en", detectedSourceCode(" EN_us "))
        assertNull(detectedSourceCode("undefined"))
        val slices = languageRescueSlices(40L * SAMPLE_RATE, 500L * SAMPLE_RATE)
        assertEquals(4, slices.size)
        assertTrue(slices.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(slices.last().end <= (40L + 100) * SAMPLE_RATE)
    }

    private fun speech(code: String, at: Long, text: String) = SpeechResult(code, listOf(SpeechSegment(at, at + 200, text)))

    private fun pipeline(translate: (String, Int) -> String? = { _, _ -> error("Translator should not load") },
                         infer: (ShortArray, String) -> SpeechResult): OfflinePipeline {
        val weights = tmp.newFile()
        return OfflinePipeline(modelPath = { weights }, engines = object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech = object : LocalSpeech {
                override fun decode(pcm: ShortArray, language: String, prompt: String, fast: Boolean, progress: WhisperProgress): SpeechResult = infer(pcm, language)
                override fun close() = Unit
            }
            override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator = object : LocalTranslator {
                override fun translate(prompt: String, seed: Int) = translate.invoke(prompt, seed)
                override fun close() = Unit
            }
        }, pcmDecoder = { _, dest, _, _ ->
            dest.writeBytes(ByteArray(70 * SAMPLE_RATE * 2) { if (it % 2 == 0) 4 else 0 })
        })
    }
}
