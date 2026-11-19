package com.ahcrazy.video2

import android.content.ContextWrapper
import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiReaction
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.GeminiTake
import com.ahcrazy.video2.core.dubbing.GeminiTone
import com.ahcrazy.video2.core.dubbing.monoPcm16Header
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.dubbing.DubSelection
import com.ahcrazy.video2.data.dubbing.GeminiDubApi
import com.ahcrazy.video2.data.dubbing.GeminiDubConfig
import com.ahcrazy.video2.data.dubbing.GeminiDubMedia
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.data.dubbing.GeminiDubSession
import com.ahcrazy.video2.data.dubbing.GeminiStep
import com.ahcrazy.video2.data.dubbing.analyzeGeminiDub
import com.ahcrazy.video2.data.dubbing.auditionDub
import com.ahcrazy.video2.data.dubbing.directGeminiAudio
import com.ahcrazy.video2.data.dubbing.geminiDubHttp
import com.ahcrazy.video2.data.dubbing.loadDubSource
import com.ahcrazy.video2.data.dubbing.saveDub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiDubbingTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = requireNotNull(GeminiDubConfig.from("fixture-api-key"))
    private val options = GeminiOptions(true, true, egyptianArabic = true)

    @Test fun mockedCastAndPerformancePreviewAndSaveKeepTheWordsAndExactCueStarts() = runBlocking<Unit> {
        val ctx = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = temp.root }
        val library = LibraryStore(ctx)
        val subs = temp.newFile("captions.srt").apply { writeText("""
            1
            00:00:01,000 --> 00:00:01,900
            إنت سمعت؟

            2
            00:00:03,000 --> 00:00:03,900
            أيوه، سمعت.

            3
            00:00:05,000 --> 00:00:05,900
            خلّينا نروح.
        """.trimIndent()) }
        val original = subs.readText()
        val mediaFile = temp.newFile("fixture.mp4").apply { writeText("never upload these video bytes") }
        val video = SavedVideo(id = "fixture-gemini", title = "private video title", sourceUrl = "https://example.invalid/private-video",
            localPath = mediaFile.path, durationSec = 7.0, subtitleFiles = mapOf("target" to subs.path), subtitleTargetLang = "ar")
        library.add(video)
        val source = loadDubSource(video)
        val media = GeminiDubMedia.from(video, source)
        val pcm = temp.newFile("audio.pcm").apply { writeBytes(ByteArray(7 * 16_000 * 2) { (it % 251).toByte() }) }
        val requests = ArrayList<Pair<Request, JSONObject>>()
        val speech = wav(24_000)
        val api = GeminiDubApi(config, geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body!!.writeTo(it) }
            check(buffer.size == request.body!!.contentLength())
            requests += request to JSONObject(buffer.readUtf8())
            val parts = when (request.url.encodedPath.substringAfterLast('/').substringBefore(':')) {
                GeminiDubModel.TRANSCRIBE.id -> arrayOf(wordPart("spk:0", "1.100s", "1.640s"), wordPart("spk_2", "3.050s", "3.700s"),
                    wordPart("spk_3", "5.100s", "5.650s"), wordPart("spk:0", "NaNs", "1.800s"))
                GeminiDubModel.DIRECT.id -> arrayOf(JSONObject().put("text", """{
                    "speakers":[{"id":1,"voice":"masculine"},{"id":2,"voice":"feminine"},{"id":3,"voice":"masculine"},
                                {"id":99,"voice":"feminine"}],
                    "cues":[{"id":1,"tone":"surprised","reaction":"gasp","placement":"before","text":"Wrong words"},
                            {"id":2,"tone":"warm","reaction":"laugh","placement":"after"},
                            {"id":3,"tone":"natural","reaction":"<untrusted>","placement":"after"}]}"""))
                else -> arrayOf(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                    .put("data", Base64.getEncoder().encodeToString(speech))))
            }
            reply(request, 200, ok(*parts))
        }.build())
        assertEquals(DubIssue.GEMINI_CONSENT, failure {
            analyzeGeminiDub(temp.root, source, media, api, options.copy(allowText = false)) {}
        }.issue)
        assertEquals(0, requests.size)
        val stages = mutableListOf<GeminiStep>()
        val direction = directGeminiAudio(pcm, source.plan, api) { stages += it.step }
        assertEquals(listOf(GeminiStep.ASSIGN_SPEAKERS, GeminiStep.DIRECT_SCENES), stages)
        assertEquals(listOf(GeminiRegister.MASCULINE, GeminiRegister.FEMININE, GeminiRegister.MASCULINE), direction.cast.map { it.register })
        assertEquals(3, direction.cast.map { it.preset.name }.distinct().size)
        assertEquals(GeminiReaction.GASP, direction.performance[1]?.reaction)
        assertEquals(GeminiTone.SURPRISED, direction.performance[1]?.tone)
        assertNull(direction.performance[3]?.reaction)
        val selection = DubSelection.Gemini(GeminiDubSession(source, media, direction), api, options)
        val preview = temp.newFile("preview.wav")
        auditionDub(ctx, source, selection, preview)
        assertEquals(5, requests.size) // one diarization, one direction request, three turns
        assertEquals(1_000, readSpeechWav(preview).samples.first().toInt())
        saveDub(ctx, library, video, source, selection, { false }, { _, _ -> })
        assertEquals(5, requests.size) // same synthesized takes for export, no second analysis/upload
        val saved = requireNotNull(library.byId(video.id))
        assertEquals("gemini-scene", saved.dubbedInfo?.provider)
        val waveform = readSpeechWav(File(requireNotNull(saved.dubbedAudioPath))).samples
        assertEquals(7 * DUB_SAMPLE_RATE, waveform.size)
        for (at in listOf(1, 3, 5).map { it * DUB_SAMPLE_RATE }) {
            assertEquals(0, waveform[at - 1].toInt())
            assertEquals(1_000, waveform[at].toInt())
        }
        assertEquals(original, subs.readText())
        val (request, diarize) = requests[0]
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertNull(request.url.query)
        assertEquals("fixture-api-key", request.header("x-goog-api-key"))
        assertNull(request.header("Cookie"))
        assertTrue(request.body!!.isOneShot())
        val transcription = diarize.getJSONObject("generationConfig").getJSONObject("audioTranscriptionConfig")
        assertTrue(transcription.getBoolean("diarization"))
        assertTrue(transcription.getBoolean("wordTimestamp"))
        val inline = parts(diarize).getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", inline.getString("mimeType"))
        assertArrayEquals(monoPcm16Header(16_000, pcm.length()) + pcm.readBytes(), Base64.getDecoder().decode(inline.getString("data")))
        assertFalse(requests[1].second.toString().contains("إنت سمعت"))
        for ((i, item) in requests.drop(2).withIndex()) {
            val body = item.second
            val text = parts(body).getJSONObject(0)
            assertEquals(1, parts(body).length())
            assertFalse(text.has("inlineData"))
            val config = body.getJSONObject("generationConfig").getJSONObject("speechConfig")
            assertEquals(direction.voiceFor(i + 1).name, config.getJSONObject("voiceConfig").getString("voice"))
            assertEquals("ar-EG", config.getString("languageCode"))
        }
        assertEquals("<gasp> إنت سمعت؟", parts(requests[2].second).getJSONObject(0).getString("text"))
        assertEquals("audibly surprised", parts(requests[2].second).getJSONObject(0).getJSONObject("speech_metadata").getString("style"))
        assertEquals("أيوه، سمعت. <laugh>", parts(requests[3].second).getJSONObject(0).getString("text"))
        assertEquals("خلّينا نروح.", parts(requests[4].second).getJSONObject(0).getString("text"))
        assertTrue(requests.none { it.second.toString().contains("private-video") || it.second.toString().contains("private video title") })
        assertEquals(DubIssue.GEMINI_LOCAL, try {
            GeminiDubMedia.from(video.copy(kind = MediaKind.HLS), source); null
        } catch (e: DubFailure) { e.issue })
        mediaFile.appendText("changed")
        assertEquals(DubIssue.SOURCE_CHANGED, failure { auditionDub(ctx, source, selection, preview) }.issue)
        assertEquals(5, requests.size)
    }

    @Test fun quotaKeyModelAndMalformedAudioAreSanitizedWithoutFallback() = runBlocking<Unit> {
        assertFalse(geminiDubHttp.followRedirects)
        assertFalse(geminiDubHttp.retryOnConnectionFailure)
        assertFalse(config.toString().contains("fixture-api-key"))
        assertNull(GeminiDubConfig.from("bad\nkey"))
        val take = GeminiTake("Kore", "صباح الخير", "warm and friendly", "ar-EG")
        val cases = listOf(
            Triple(429, "a private transcript in error", DubIssue.GEMINI_LIMIT),
            Triple(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""", DubIssue.GEMINI_AUTH),
            Triple(404, "a key", DubIssue.GEMINI_MODEL), Triple(302, "do not redirect", DubIssue.GEMINI_SERVICE),
            Triple(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""", DubIssue.GEMINI_BLOCKED),
            Triple(200, ok(JSONObject("""{"inlineData":{"mimeType":"audio/wav","data":"***"}}""")), DubIssue.AUDIO_FORMAT)
        )
        for ((status, body, issue) in cases) {
            var requests = 0
            val client = geminiDubHttp.newBuilder().addInterceptor { chain -> requests++; reply(chain.request(), status, body) }.build()
            val error = failure { GeminiDubApi(config, client).synthesize(take, 17) }
            assertEquals(issue, error.issue)
            assertEquals(17, error.line)
            assertEquals(issue.name, error.message)
            assertEquals(1, requests)
        }
    }

    @Test fun cancellingAnalysisCancelsTheActualCall() = runBlocking<Unit> {
        val queued = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = object : Call {
                override fun request() = request
                override fun execute(): Response = throw IOException("not used")
                override fun enqueue(responseCallback: Callback) { queued.complete(Unit) }
                override fun cancel() { canceled.complete(Unit) }
                override fun isExecuted() = queued.isCompleted
                override fun isCanceled() = canceled.isCompleted
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = newCall(request)
            }
        }
        val pcm = temp.newFile().apply { writeBytes(ByteArray(4)) }
        val job = launch { GeminiDubApi(config, factory).speakers(com.ahcrazy.video2.data.dubbing.GeminiAudioSlice(pcm, 0, 2)) }
        withTimeout(2_000) { queued.await() }
        job.cancelAndJoin()
        assertTrue(canceled.isCompleted)
    }

    private suspend fun failure(block: suspend () -> Unit): DubFailure = try {
        block()
        throw AssertionError("Expected DubFailure")
    } catch (e: DubFailure) { e }

    private fun parts(body: JSONObject) = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
    private fun ok(vararg parts: JSONObject) = JSONObject().put("candidates", JSONArray(listOf(JSONObject().put("finishReason", "STOP")
        .put("content", JSONObject().put("parts", JSONArray(parts.toList())))))).toString()
    private fun wordPart(label: String, start: String, end: String) = JSONObject().put("audioTranscription",
        JSONObject().put("speakerLabel", label).put("words", JSONArray(listOf(JSONObject().put("word", "fixture")
            .put("startOffset", start).put("endOffset", end)))))
    private fun reply(request: Request, status: Int, json: String) = Response.Builder().protocol(Protocol.HTTP_1_1)
        .request(request).code(status).message("fixture").body(json.toResponseBody()).build()
    private fun wav(rate: Int): ByteArray = monoPcm16Header(rate, 12) + byteArrayOf(-24, 3, -24, 3, -24, 3, -24, 3, -24, 3, -24, 3)
}
