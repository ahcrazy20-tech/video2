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

    @Test fun restartsFromSpeechAndTranslationCheckpointsAndNeverOverlapsModels() {
        val dir = tmp.newFolder()
        val input = tmp.newFile().apply { writeText("dummy container") }
        var exports = 0
        var session: String? = null
        var decodeCalls = 0
        var translateCalls = 0
        val loader = object : LocalEngines {
            override fun speech(model: File, signal: CancellationSignal): LocalSpeech {
                assertNull(session); session = "speech"
                return object : LocalSpeech {
                    override fun decode(pcm: ShortArray, language: String, prompt: String): SpeechResult {
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
        val pipeline = OfflinePipeline({ input }, loader) { _, target, _, progress ->
            exports++
            RandomAccessFile(target, "rw").use { audio -> // one nonzero sample in each read window
                audio.setLength(55L * SAMPLE_RATE * 2)
                audio.writeShort(500)
                audio.seek(52L * SAMPLE_RATE * 2)
                audio.writeShort(500)
            }
            progress(1.0)
        }
        fun run() = pipeline.run(dir, input, SubLang.AUTO, SubLang.AR, OfflineModel.HYMT_Q4, CancellationSignal()) { _, _, _ -> }
        assertThrows(OperationCanceledException::class.java) { run() }
        assertEquals(0, translateCalls)
        assertNull(session)
        assertThrows(OperationCanceledException::class.java) { run() }
        assertEquals(3, decodeCalls)
        val cues = run()
        assertEquals(1, exports)
        assertEquals(listOf("صباح الخير", "مع السلامة"), cues.map { it.translated })
        assertEquals(listOf(1.0, 53.0), cues.map { it.startSec })
        assertEquals(cues.size, SrtCodec.parse(SrtCodec.write(cues, "bilingual")).size)
        assertTrue(SrtCodec.write(cues, "bilingual").contains("Good morning\nصباح الخير"))
        assertNull(session)
        assertFalse(File(dir, "audio-16k.pcm").exists())
        input.appendText("new file content")
        assertEquals(OfflineIssue.VIDEO, assertThrows(OfflineFailure::class.java) { run() }.issue)
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
        assertTrue(OfflineModel.HYMT_Q6.spec.bytes > Int.MAX_VALUE)
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
             {"id":"legacy","videoId":"test-video","videoTitle":"Title","phase":"DONE"}]
        """.trimIndent())
        val manager = TranslationManager(app, library, AppSettings(app), models, work)
        assertEquals(listOf(TranslationMode.ON_DEVICE, TranslationMode.ON_DEVICE, TranslationMode.CLOUD), manager.jobs.value.map { it.mode })
        assertEquals(TranslationPhase.PAUSED, manager.jobs.value.first().phase)
        manager.resume("offline")
        manager.resume("future")
        assertEquals(listOf(TranslationPhase.FAILED, TranslationPhase.FAILED, TranslationPhase.DONE), manager.jobs.value.map { it.phase })
        assertNull(work.status.value)
        assertTrue(library.attachSubtitles(video.id, video.localPath, mapOf("orig" to "/local.srt"), "ar"))
        library.updatePosition(video, 5.0, 22.0) // the player still holds the old SavedVideo
        assertEquals("/local.srt", library.byId(video.id)?.subtitleFiles?.get("orig"))
        assertEquals(5.0, library.byId(video.id)!!.lastPositionSec, 0.0)
    }
}
