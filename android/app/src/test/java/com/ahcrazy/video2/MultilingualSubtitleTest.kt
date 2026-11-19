package com.ahcrazy.video2

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.data.offline.*
import org.json.JSONObject
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
class MultilingualSubtitleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val speech = OfflineModel.WHISPER_SMALL_Q5
    private val arabic = OfflineModel.FIREFOX_EN_AR
    private val spanish = OfflineModel.FIREFOX_ES_AR
    private val italian = OfflineModel.FIREFOX_IT_AR

    @Test fun routesRequireTheirLanguageAndOnlyOneSharedArabicBundle() {
        assertEquals(SubLang.IT, SubLang.of("it"))
        assertEquals(spanish, OfflineModel.firefoxFor("es", "ar"))
        assertEquals(italian, OfflineModel.firefoxFor("it", "ar"))
        assertNull(OfflineModel.firefoxFor("es", "en"))
        assertNull(OfflineModel.firefoxFor("auto", "ar"))
        assertFalse(supportsLocalPair("it", "ar", speech, spanish))
        assertFalse(supportsLocalPair("es", "ar", OfflineModel.MOONSHINE_EN, spanish))
        assertTrue(supportsLocalPair("es", "ar", speech, spanish))
        assertTrue(supportsLocalPair("it", "ar", speech, italian))
        assertEquals(listOf(speech, spanish, arabic, OfflineModel.SILERO_VAD), OfflineModel.pack(speech, spanish, true))
        assertEquals(72_579_159L, spanish.requiredBundles.sumOf { it.bytes })
        assertEquals(72_653_197L, italian.requiredBundles.sumOf { it.bytes })
        assertEquals(3, (spanish.requiredBundles + italian.requiredBundles).distinct().size)
    }

    @Test fun pivotKeepsCueOrderAndCommitsOnlyWholeBatchesThenContextUsesIdenticalSource() {
        for ((lang, route) in listOf(SubLang.ES to spanish, SubLang.IT to italian)) {
            val video = tmp.newFile().apply { writeText("local ${lang.code} video") }
            val saved = tmp.newFolder()
            val fast = tmp.newFolder()
            val contextual = tmp.newFolder()
            val content = if (lang == SubLang.ES) "¿Ya estás lista, Ana?" else "Sei già pronta, Anna?"
            val cues = (0 until 24).map { SubCue(it, it * 1.8, it * 1.8 + 1.4, "$content $it") }
            val previousCues = cues.map { it.copy(translated = "old translation") }
            seedTranscript(saved, video, previousCues, lang)
            val paths = mutableListOf<OfflineModel>()
            val batches = mutableListOf<List<String>>()
            var badSecondBatch = true
            var contextSeen = false
            val pipeline = OfflinePipeline({ paths += it; File(saved, it.id) }, object : LocalEngines {
                override fun speech(model: File, signal: CancellationSignal): LocalSpeech = error("Reuse, not ASR")
                override fun translatorPivot(model: File, arabic: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                    assertEquals(route, kind)
                    assertEquals(route.id, model.name)
                    assertEquals(this@MultilingualSubtitleTest.arabic.id, arabic.name)
                    return object : LocalTranslator {
                        override fun translateBatch(lines: List<String>): List<String> {
                            batches += lines
                            return if (badSecondBatch && batches.size == 2) lines.mapIndexed { i, text ->
                                if (i == 1) " " else "نص سريع ${text.substringAfterLast(' ')}"
                            } else lines.map { "نص سريع ${it.substringAfterLast(' ')}" }
                        }
                        override fun close() = Unit
                    }
                }
                override fun translator(model: File, kind: OfflineModel, signal: CancellationSignal): LocalTranslator {
                    assertEquals(OfflineModel.HYMT_LITE, kind)
                    return object : LocalTranslator {
                        override fun translate(prompt: String, seed: Int): String {
                            if ("Following lines: $content 6" in prompt) contextSeen = true
                            return prompt.substringAfter("[Source Text]\n").trim().split(SUBTITLE_DELIMITER)
                                .joinToString(SUBTITLE_DELIMITER) { "نص سياقي ${it.trim().substringAfterLast(' ')}" }
                        }
                        override fun close() = Unit
                    }
                }
            }, pcmDecoder = { _, _, _, _ -> error("Reuse, not extraction") })
            fun run(dir: File, model: OfflineModel, stats: OfflineStats = OfflineStats()) =
                pipeline.run(dir, video, lang, SubLang.AR, speech, CancellationSignal(), translator = model, stats = stats) { _, _, _, _ -> }
            pipeline.reuseTranscript(saved, fast, video, CancellationSignal(), expectedSource = lang)
            assertEquals(OfflineIssue.TRANSLATION, assertThrows(OfflineFailure::class.java) { run(fast, route) }.issue)
            val cp = OfflinePipeline.translationCheckpoint(fast, SubLang.AR, route)
            assertTrue(hasCheckpoint(File(cp, "0.json")))
            assertFalse(hasCheckpoint(File(cp, "1.json")))
            assertFalse(hasCheckpoint(OfflinePipeline.translatedCuesFile(fast, "ar", route)))
            badSecondBatch = false
            val stats = OfflineStats()
            val pivot = run(fast, route, stats)
            assertEquals(cues.map { it.copy(translated = "نص سريع ${it.index}") }, pivot)
            assertEquals(listOf(20, 4, 4), batches.map { it.size })
            assertEquals(batches[1], batches[2])
            assertEquals(1, stats.report().reusedBatches)
            assertTrue(stats.report().reusedTranscript)
            // A new job can retranslate the fast route's source checkpoint, not its Arabic output.
            val fastResult = OfflinePipeline.translatedCuesFile(fast, "ar", route)
            CueJson.write(fastResult, pivot)
            pipeline.reuseTranscript(fast, contextual, video, CancellationSignal(), expectedSource = lang)
            assertEquals(cues.map { it.copy(translated = "نص سياقي ${it.index}") }, run(contextual, OfflineModel.HYMT_LITE))
            assertTrue(contextSeen)
            assertEquals(listOf(route, arabic, route, arabic, OfflineModel.HYMT_LITE), paths)
            assertEquals(pivot, CueJson.read(fastResult))
            assertEquals(previousCues, CueJson.read(File(saved, "cues-original.json")))
        }
    }

    @Test fun uncheckedOrChangedMediaCannotSupplyTheNewJobWithATranscript() {
        val video = tmp.newFile().apply { writeText("private video") }
        val saved = tmp.newFolder()
        seedTranscript(saved, video, listOf(SubCue(0, 1.0, 2.0, "Sono qui, Anna.")), SubLang.IT)
        val pipeline = OfflinePipeline({ error("No models needed") })
        val wrongLanguage = tmp.newFolder()
        assertEquals(OfflineIssue.LANGUAGE, assertThrows(OfflineFailure::class.java) {
            pipeline.reuseTranscript(saved, wrongLanguage, video, CancellationSignal(), expectedSource = SubLang.ES)
        }.issue)
        assertFalse(hasCheckpoint(File(wrongLanguage, "cues-original.json")))
        val otherVideo = tmp.newFile().apply { writeText(video.readText()); setLastModified(video.lastModified()) }
        assertEquals(OfflineIssue.VIDEO, assertThrows(OfflineFailure::class.java) {
            pipeline.reuseTranscript(saved, tmp.newFolder(), otherVideo, CancellationSignal(), expectedSource = SubLang.IT)
        }.issue)
        val signal = CancellationSignal().apply { cancel() }
        assertThrows(OperationCanceledException::class.java) { pipeline.reuseTranscript(saved, tmp.newFolder(), video, signal) }
    }

    private fun seedTranscript(dir: File, video: File, cues: List<SubCue>, source: SubLang) {
        atomicText(File(dir, "offline-source.json"), JSONObject().put("path", video.absolutePath)
            .put("size", video.length()).put("mtime", video.lastModified()).toString())
        atomicText(File(dir, "offline-language.txt"), source.code)
        CueJson.write(File(dir, "cues-original.json"), cues)
    }
}
