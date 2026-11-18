package com.ahcrazy.video2

import android.app.Application
import android.media.AudioFormat
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationMode
import com.ahcrazy.video2.core.model.TranslationPhase
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.TranslationManager
import com.ahcrazy.video2.data.offline.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineSubtitleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pcmDownmixResamplingAndAudioDelayKeepVideoTiming() {
        val result = ByteArrayOutputStream()
        val sink = TimedPcmSink(result)
        val spec = PcmFormat(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        fun stereo() = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(480) { putShort(20_000); putShort(4_000) }
            flip()
        }
        sink.append(stereo(), spec, 250_000)
        sink.append(stereo(), spec, 260_000)
        sink.append(stereo(), spec, 450_000)
        sink.finish()
        val pcm = ByteBuffer.wrap(result.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertTrue(sink.hasAudio)
        assertEquals(7360, pcm.remaining())
        assertEquals(0, pcm[3999].toInt())
        assertEquals(12_000, pcm[4000].toInt())
        assertEquals(12_000, pcm[4319].toInt())
        assertEquals(0, pcm[4320].toInt())
        assertEquals(12_000, pcm[7200].toInt())
    }

    @Test fun resamplerPreservesSpeechBandAndRejectsHighFrequencyAlias() {
        fun rms(hz: Int): Double {
            val list = mutableListOf<Float>()
            val filter = SpeechResampler(44_100) { list.add(it) }
            repeat(44_100) { filter.push(sin(2 * PI * hz * it / 44_100).toFloat()) }
            filter.finish()
            assertEquals(16_000, list.size)
            return sqrt(list.drop(100).dropLast(100).map { it.toDouble() * it }.average())
        }
        assertTrue(rms(1_000) > 0.68)
        assertTrue(rms(12_000) < 0.01)
    }

    @Test fun overlapStitchesAcrossWindowBoundaryWithoutRepeatingSharedWords() {
        val prior = SubCue(0, 47.0, 50.2, "we keep these words")
        val part = SpeechWindow.at(1, 70L * SAMPLE_RATE)
        val result = SpeechResult("en", listOf(
            SpeechSegment(30, 110, "old context"),
            SpeechSegment(340, 580, "these words, even at the boundary"),
            SpeechSegment(650, 720, "and this too")
        ))
        val cues = cuesInWindow(part, result, prior, 1)
        assertEquals(listOf("even at the boundary", "and this too"), cues.map { it.original })
        assertEquals(50.2, cues[0].startSec, 0.001)
        assertEquals(51.8, cues[0].endSec, 0.001)
        assertEquals(2, cues.last().index)
    }

    @Test fun arabicSystemLocaleStillWritesPortableSrtTimecodes() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            val cue = SubCue(0, 1.001, 2.02, "hello", "مرحبًا")
            val srt = SrtCodec.write(listOf(cue), "bilingual")
            assertTrue(srt.contains("00:00:01,001 --> 00:00:02,020"))
            assertEquals(cue.startSec, SrtCodec.parse(srt).single().startSec, 0.00001)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun translationRequiresAlignedDelimitersAndDoesNotSubstituteUntranslatedText() {
        val lines = listOf("hello", "listen")
        val aligned = translateLines(lines, "Arabic", 5) { prompt, _ ->
            assertTrue(prompt.contains("Arabic"))
            "مرحبًا\n$SUBTITLE_DELIMITER\nاستمع"
        }
        assertEquals(listOf("مرحبًا", "استمع"), aligned)
        var tries = 0
        val retry = translateLines(lines, "Arabic", 5) { prompt, _ ->
            tries++
            when {
                SUBTITLE_DELIMITER in prompt -> "إجابة مدمجة لا تطابق السطرين"
                prompt.endsWith("hello") -> "مرحبًا"
                else -> "استمع"
            }
        }
        assertEquals(aligned, retry)
        assertEquals(3, tries)
        val error = assertThrows(OfflineFailure::class.java) { translateLines(listOf("hello"), "Arabic", 5) { _, _ -> null } }
        assertEquals(OfflineIssue.TRANSLATION, error.issue)
    }

    @Test fun givesTranslationNearbyContextButNotUnrelatedScenesOrExtraOutput() {
        val cues = listOf(
            SubCue(0, 0.0, 1.0, "Earlier scene"),
            SubCue(1, 10.0, 11.0, "They reached the river at sunset."),
            SubCue(2, 11.0, 12.0, "She stood on the bank."),
            SubCue(3, 12.0, 13.0, "The water was rising."),
            SubCue(4, 13.0, 14.0, "They moved uphill."),
            SubCue(5, 28.0, 30.0, "Different topic")
        )
        val context = contextForBatch(cues, 2, 1)
        assertEquals(listOf(cues[1].original), context.before)
        assertEquals(cues.subList(3, 5).map { it.original }, context.after)
        val translated = translateLines(listOf(cues[2].original), "Arabic", 7, context) { prompt, _ ->
            assertTrue(prompt.contains("Previous lines: They reached the river"))
            assertTrue(prompt.contains("Following lines: The water was rising."))
            assertFalse(prompt.contains("Earlier scene"))
            assertFalse(prompt.contains("Different topic"))
            assertEquals(cues[2].original, prompt.substringAfter("[Source Text]\n"))
            "وقفت على الضفة."
        }
        assertEquals(listOf("وقفت على الضفة."), translated)
    }

    @Test fun retainsContextOnSplitRetryAndDoesNotAcceptEchoedBackground() {
        val lines = listOf("The leaves were dry.", "She struck a match.")
        val context = SubtitleContext(before = listOf("They collected kindling."), after = listOf("The fire caught."))
        val translated = translateLines(lines, "Arabic", 7, context) { prompt, _ ->
            when (prompt.substringAfter("[Source Text]\n")) {
                lines.joinToString("\n$SUBTITLE_DELIMITER\n") -> "[Background Information] ناتج غير صالح"
                lines[0] -> {
                    assertTrue(prompt.contains("Following lines: She struck a match. The fire caught."))
                    "كانت الأوراق جافة."
                }
                lines[1] -> {
                    assertTrue(prompt.contains("Previous lines: They collected kindling. The leaves were dry."))
                    "أشعلت عود ثقاب."
                }
                else -> error("Unexpected subtitle prompt")
            }
        }
        assertEquals(listOf("كانت الأوراق جافة.", "أشعلت عود ثقاب."), translated)
        var calls = 0
        val single = translateLines(listOf(lines[1]), "Arabic", 7, context) { prompt, _ ->
            calls++
            if (prompt.contains("[Background Information]")) "[Background Information] كلمات زيادة" else "أشعلت عود ثقاب."
        }
        assertEquals(listOf("أشعلت عود ثقاب."), single)
        assertEquals(2, calls)
    }

    @Test fun bothSpeechModelsUseTheSameQ4WeightsAndMediumDoesNotPullLarge() {
        val medium = OfflineModel.WHISPER_MEDIUM
        val large = OfflineModel.WHISPER_LARGE
        val q4 = OfflineModel.HYMT_Q4
        assertEquals(listOf(medium, q4), OfflineModel.pack(medium))
        assertEquals(listOf(large, q4), OfflineModel.pack(large))
        assertEquals(6_158_411_955L, OfflineModel.pack(medium).sumOf { it.spec.bytes })
        assertTrue(medium.requiredFreeRam < large.requiredFreeRam)
        assertEquals(medium, OfflineModel.speechForJob(medium.id))
        assertEquals(large, OfflineModel.speechForJob(null)) // 1.7 jobs implicitly used Large-v3
        assertNull(OfflineModel.speechForJob(q4.id))
        assertNull(OfflineModel.speechForJob("unavailable-whisper"))
        assertEquals(listOf(q4), OfflineModel.entries.filter { it.extension == "gguf" })
    }

    @Test fun restartsFromSpeechAndTranslationCheckpointsAndNeverOverlapsModels() {
        val dir = tmp.newFolder()
        val input = tmp.newFile().apply { writeText("dummy container") }
        var exports = 0
        var session: String? = null
        var decodeCalls = 0
        var translateCalls = 0
        val transient = mutableListOf<Double>()
        val loader = object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech {
                assertNull(session); session = "speech"
                return object : LocalSpeech {
                    override fun decode(pcm: ShortArray, language: String, prompt: String,
                                        fast: Boolean, progress: WhisperProgress): SpeechResult {
                        assertTrue(fast)
                        progress.update(30)
                        progress.update(20) // native retry/regression is not reported to the UI
                        decodeCalls++
                        return when (decodeCalls) {
                            1 -> { assertEquals("auto", language); SpeechResult("en", listOf(SpeechSegment(100, 250, "Good morning"))) }
                            2 -> { signal.cancel(); signal.throwIfCanceled(); error("cancel") }
                            3 -> { assertEquals("en", language); SpeechResult("en", listOf(SpeechSegment(700, 800, "Goodbye"))) }
                            else -> error("reran speech")
                        }
                    }
                    override fun close() { assertEquals("speech", session); session = null }
                }
            }
            override fun translator(model: File, signal: CancellationSignal): LocalTranslator {
                assertNull(session); session = "translator"
                return object : LocalTranslator {
                    override fun translate(prompt: String, seed: Int): String? {
                        translateCalls++
                        return when (translateCalls) {
                            1 -> { assertTrue(prompt.endsWith("Good morning")); "صباح الخير" }
                            2 -> { signal.cancel(); signal.throwIfCanceled(); error("cancel") }
                            3 -> { assertTrue(prompt.endsWith("Goodbye")); "مع السلامة" }
                            else -> error("reran translation")
                        }
                    }
                    override fun close() { assertEquals("translator", session); session = null }
                }
            }
        }
        val modelLoads = mutableListOf<OfflineModel>()
        val pipeline = OfflinePipeline({ modelLoads += it; input }, loader) { _, target, _, progress ->
            exports++
            RandomAccessFile(target, "rw").use { audio -> // one nonzero sample in each read window
                audio.setLength(55L * SAMPLE_RATE * 2)
                audio.writeShort(500)
                audio.seek(52L * SAMPLE_RATE * 2)
                audio.writeShort(500)
            }
            progress(1.0)
        }
        fun run() = pipeline.run(dir, input, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM,
            CancellationSignal(), fastSpeech = true) { phase, p, _, checkpoint ->
                if (phase == TranslationPhase.TRANSCRIBING && !checkpoint) transient += p
            }
        assertThrows(OperationCanceledException::class.java) { run() }
        assertEquals(0, translateCalls)
        assertNull(session)
        assertThrows(OperationCanceledException::class.java) { run() }
        assertEquals(3, decodeCalls)
        val cues = run()
        assertEquals(1, exports)
        assertEquals(3, transient.size)
        assertEquals(0.15 + 0.44 * 0.3 / 2, transient[0], 0.0001)
        assertEquals(transient[1], transient[2], 0.0001)
        assertEquals(listOf(OfflineModel.WHISPER_MEDIUM, OfflineModel.WHISPER_MEDIUM,
            OfflineModel.HYMT_Q4, OfflineModel.HYMT_Q4), modelLoads)
        assertEquals(listOf("صباح الخير", "مع السلامة"), cues.map { it.translated })
        assertEquals(listOf(1.0, 53.0), cues.map { it.startSec })
        assertEquals(cues.size, SrtCodec.parse(SrtCodec.write(cues, "bilingual")).size)
        assertTrue(SrtCodec.write(cues, "bilingual").contains("Good morning\nصباح الخير"))
        assertNull(session)
        assertFalse(File(dir, "audio-16k.pcm").exists())
        input.appendText("new file content")
        assertEquals(OfflineIssue.VIDEO, assertThrows(OfflineFailure::class.java) { run() }.issue)
    }

    @Test fun loadsMmapTranslatorWithoutRequiringAllWeightsInFreeRamButHonorsAndroidPressure() {
        val q4 = OfflineModel.HYMT_Q4
        val phone = 11_440 * MIB
        val androidThreshold = 640 * MIB
        val ramBeforeTranslation = 4_600 * MIB // would fail the old 5000 MiB gate
        assertTrue(canOpenModel(q4, phone, ramBeforeTranslation, androidThreshold, false))
        assertFalse(canOpenModel(q4, phone, ramBeforeTranslation, androidThreshold, true))
        assertFalse(canOpenModel(q4, phone, 3_000 * MIB, androidThreshold, false))
        assertFalse(canOpenModel(q4, 7_300 * MIB, 3_500 * MIB, androidThreshold, false))
        assertFalse(canOpenModel(q4, phone, 4_800 * MIB, 4_600 * MIB, false))
        assertFalse(translationUnderPressure(1_500 * MIB, androidThreshold, false))
        assertTrue(translationUnderPressure(800 * MIB, androidThreshold, false))
        assertTrue(translationUnderPressure(1_500 * MIB, androidThreshold, true))
    }

    @Test fun resumesEighteenTranscriptAt62PercentAndDoesNotRepeatCompletedBatchesOrExportInference() {
        val dir = tmp.newFolder()
        val input = tmp.newFile().apply { writeText("old video container") }
        val cues = listOf(SubCue(0, 1.0, 2.0, "First line"), SubCue(1, 15.0, 16.0, "Second line"))
        File(dir, "offline-source.json").writeText(JSONObject().put("path", input.absolutePath).put("size", input.length())
            .put("mtime", input.lastModified()).toString())
        File(dir, "offline-language.txt").writeText("en")
        // Android AtomicFile recovery is needed when only the .bak from an older write survives.
        File(dir, "cues-original.json.bak").writeText(CueJson.encode(cues).toString())
        var gate = true
        var underPressure = false
        var pauseAfterFirst = true
        var sessions = 0
        var closed = 0
        val prompts = mutableListOf<String>()
        val pipeline = OfflinePipeline({ model ->
            assertEquals(OfflineModel.HYMT_Q4, model)
            if (gate) throw OfflineFailure(OfflineIssue.TRANSLATOR_MEMORY)
            input
        }, object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech = error("Whisper must not load again")
            override fun translator(model: File, signal: CancellationSignal): LocalTranslator {
                sessions++
                return object : LocalTranslator {
                    override fun close() { closed++ }
                    override fun translate(prompt: String, seed: Int): String {
                        prompts += prompt.substringAfter("[Source Text]\n")
                        if (pauseAfterFirst) underPressure = true
                        return if (prompt.endsWith("First line")) "الأول" else "الثاني"
                    }
                }
            }
        }, checkTranslationMemory = { if (underPressure) throw OfflineFailure(OfflineIssue.TRANSLATOR_MEMORY) }) { _, _, _, _ ->
            error("PCM must not be decoded again")
        }
        var savedProgress = 0.0
        fun run() = pipeline.run(dir, input, SubLang.AUTO, SubLang.AR, OfflineModel.WHISPER_MEDIUM, CancellationSignal()) { phase, p, count, saved ->
            assertEquals(TranslationPhase.TRANSLATING, phase)
            assertTrue(saved)
            assertEquals(2, count)
            savedProgress = p
        }
        assertEquals(OfflineIssue.TRANSLATOR_MEMORY, assertThrows(OfflineFailure::class.java) { run() }.issue)
        assertEquals(0.62, savedProgress, 0.0001)
        assertEquals(0, sessions)
        assertEquals(cues, CueJson.read(File(dir, "cues-original.json")))
        gate = false
        assertEquals(OfflineIssue.TRANSLATOR_MEMORY, assertThrows(OfflineFailure::class.java) { run() }.issue)
        assertEquals(1, sessions)
        assertEquals(1, closed)
        assertEquals(listOf("First line"), prompts)
        assertEquals(0.79, savedProgress, 0.0001)
        pauseAfterFirst = false
        underPressure = false
        val done = run()
        assertEquals(listOf("First line", "Second line"), prompts)
        assertEquals(listOf("الأول", "الثاني"), done.map { it.translated })
        assertEquals(cues.map { it.startSec to it.endSec }, done.map { it.startSec to it.endSec })
        assertEquals(2, sessions)
        assertEquals(2, closed)
        CueJson.write(File(dir, "cues-translated-ar.json"), done)
        OfflinePipeline.translationCheckpoint(dir, SubLang.AR).deleteRecursively()
        gate = true
        assertEquals(done, run())
        assertEquals(2, sessions)
    }

    @Test fun pinsModelsAndRejectsAnInvalidRangeBeforeAppending() {
        val payload = "offline-model".encodeToByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val part = tmp.newFile().apply { writeText("offline-") }
        val model = File(tmp.root, "model.bin")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 5-9/13").setBody("model"))
            val spec = PinnedModel(server.url("/weights").toString(), payload.size.toLong(), sha)
            val client = OkHttpClient()
            assertThrows(IOException::class.java) { downloadModel(client, spec, part, model, CancellationSignal()) {} }
            assertEquals("offline-", part.readText())
            assertFalse(model.exists())
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 8-12/13").setBody("model"))
            downloadModel(client, spec, part, model, CancellationSignal()) {}
            assertArrayEquals(payload, model.readBytes())
            assertFalse(part.exists())
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
        }
        assertTrue(OfflineModel.HYMT_Q4.spec.bytes > Int.MAX_VALUE)
    }

    @Test fun whenServerIgnoresRangeTheWholeFileIsHashedAndBadWeightsNeverInstall() {
        MockWebServer().use { server ->
            val spec = PinnedModel(server.url("/weights").toString(), 4, "0".repeat(64))
            val part = tmp.newFile().apply { writeText("xx") }
            val model = File(tmp.root, "invalid.bin")
            server.enqueue(MockResponse().setBody("test"))
            val error = assertThrows(OfflineFailure::class.java) { downloadModel(OkHttpClient(), spec, part, model, CancellationSignal()) {} }
            assertEquals(OfflineIssue.CHECKSUM, error.issue)
            assertFalse(part.exists())
            assertFalse(model.exists())
        }
    }

    @Test fun retiredQ6CanBeRemovedWithoutTouchingQ4OrSavedSubtitles() {
        val app: Application = RuntimeEnvironment.getApplication()
        val models = OfflineModels(app, OfflineWork(app))
        val weights = File(app.noBackupFilesDir, "offline-models")
        val q6 = File(weights, "$RETIRED_TRANSLATOR_ID-88ef0aba5995.gguf").apply { writeText("old") }
        val partial = File(weights, "${q6.name}.part").apply { writeText("part") }
        val q4 = File(weights, "hymt2-7b-q4-9f96256500f3.gguf").apply { writeText("keep") }
        val subtitle = File(app.filesDir, "existing.srt").apply { writeText("subtitle") }
        assertEquals(7L, models.retiredBytes())
        models.removeRetired()
        assertFalse(q6.exists())
        assertFalse(partial.exists())
        assertTrue(q4.exists())
        assertTrue(subtitle.exists())
        assertEquals(0L, models.retiredBytes())
    }

    @Test fun persistedLocalWorkNeverResumesAsCloudAndSubtitleRegistrationSurvivesPlaybackUpdates() {
        val app: Application = RuntimeEnvironment.getApplication()
        val library = LibraryStore(app)
        val work = OfflineWork(app)
        val models = OfflineModels(app, work)
        val video = SavedVideo(id = "test-video", title = "Title", sourceUrl = "", localPath = "/fixture.mp4", kind = MediaKind.MP4)
        library.add(video)
        val dir = File(app.filesDir, "Translations").apply { mkdirs() }
        File(dir, "jobs.json").writeText("""
            [{"id":"offline","videoId":"test-video","videoTitle":"Title","mode":"ON_DEVICE","offlineModel":"removed","phase":"TRANSCRIBING"},
             {"id":"future","videoId":"test-video","videoTitle":"Title","mode":"FUTURE_MODE","phase":"SAVING"},
             {"id":"retired","videoId":"test-video","videoTitle":"Title","mode":"ON_DEVICE","offlineModel":"hymt2-7b-q6","phase":"PAUSED"},
             {"id":"medium","videoId":"test-video","videoTitle":"Title","mode":"ON_DEVICE","offlineModel":"hymt2-7b-q4","offlineSpeechModel":"whisper-medium","offlineFastSpeech":true,"phase":"DONE"},
             {"id":"legacy","videoId":"test-video","videoTitle":"Title","phase":"DONE"}]
        """.trimIndent())
        val manager = TranslationManager(app, library, AppSettings(app), models, work)
        assertEquals(listOf(TranslationMode.ON_DEVICE, TranslationMode.ON_DEVICE, TranslationMode.ON_DEVICE, TranslationMode.ON_DEVICE, TranslationMode.CLOUD), manager.jobs.value.map { it.mode })
        assertEquals(TranslationPhase.PAUSED, manager.jobs.value.first().phase)
        manager.resume("offline")
        manager.resume("future")
        manager.resume("retired")
        assertEquals(listOf(TranslationPhase.FAILED, TranslationPhase.FAILED, TranslationPhase.FAILED, TranslationPhase.DONE, TranslationPhase.DONE),
            manager.jobs.value.map { it.phase })
        assertEquals(OfflineIssue.RETIRED_TRANSLATOR.name, manager.jobs.value[2].offlineIssue)
        val reloaded = TranslationManager(app, library, AppSettings(app), models, work)
        assertEquals(OfflineModel.WHISPER_MEDIUM.id, reloaded.jobs.value[3].offlineSpeechModel)
        assertEquals(OfflineModel.HYMT_Q4.id, reloaded.jobs.value[3].offlineModel)
        assertTrue(reloaded.jobs.value[3].offlineFastSpeech)
        assertFalse(reloaded.jobs.value[2].offlineFastSpeech)
        assertNull(work.status.value)
        assertTrue(library.attachSubtitles(video.id, video.localPath, mapOf("orig" to "/local.srt"), "ar"))
        library.updatePosition(video, 5.0, 22.0) // the player still holds the old SavedVideo
        assertEquals("/local.srt", library.byId(video.id)?.subtitleFiles?.get("orig"))
        assertEquals(5.0, library.byId(video.id)!!.lastPositionSec, 0.0)
    }
}
