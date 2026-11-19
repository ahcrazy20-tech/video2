package com.ahcrazy.video2

import android.content.ContextWrapper
import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.GEMINI_PART_SECONDS
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiReaction
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.GeminiTake
import com.ahcrazy.video2.core.dubbing.GeminiTone
import com.ahcrazy.video2.core.dubbing.monoPcm16Header
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.core.dubbing.planDub
import com.ahcrazy.video2.core.dubbing.take
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.dubbing.DubSelection
import com.ahcrazy.video2.data.dubbing.GeminiAudioSlice
import com.ahcrazy.video2.data.dubbing.GeminiDubApi
import com.ahcrazy.video2.data.dubbing.GeminiDubConfig
import com.ahcrazy.video2.data.dubbing.GeminiDubMedia
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.data.dubbing.GeminiDubRouter
import com.ahcrazy.video2.data.dubbing.GeminiDubSession
import com.ahcrazy.video2.data.dubbing.GeminiHttpFailure
import com.ahcrazy.video2.data.dubbing.GeminiQuotaKind
import com.ahcrazy.video2.data.dubbing.GeminiRoutingEvent
import com.ahcrazy.video2.data.dubbing.GeminiStep
import com.ahcrazy.video2.data.dubbing.GeminiStage
import com.ahcrazy.video2.data.dubbing.GeminiTask
import com.ahcrazy.video2.data.dubbing.analyzeGeminiDub
import com.ahcrazy.video2.data.dubbing.auditionDub
import com.ahcrazy.video2.data.dubbing.directGeminiAudio
import com.ahcrazy.video2.data.dubbing.geminiDubHttp
import com.ahcrazy.video2.data.dubbing.loadDubSource
import com.ahcrazy.video2.data.dubbing.saveDub
import com.ahcrazy.video2.data.dubbing.testGeminiSpeech
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
import java.io.RandomAccessFile
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
        val priorTrack = File(requireNotNull(saved.dubbedAudioPath))
        var speechAttempts = 0
        val noCapacity = geminiDubHttp.newBuilder().addInterceptor { chain ->
            if (++speechAttempts == 1) reply(chain.request(), 200, ok(JSONObject().put("inlineData",
                JSONObject().put("mimeType", "audio/wav").put("data", Base64.getEncoder().encodeToString(speech)))))
            else reply(chain.request(), 429, quotaError("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "1"))
        }.build()
        val unavailable = DubSelection.Gemini(GeminiDubSession(source, media, direction),
            GeminiDubApi(config, noCapacity, GeminiDubRouter(true)), options.copy(allowModelFallback = true))
        val exhausted = failure { saveDub(ctx, library, saved, source, unavailable, { false }, { _, _ -> }) } as GeminiHttpFailure
        assertEquals(GeminiQuotaKind.DAILY, exhausted.quota?.kind)
        assertEquals(GeminiDubModel.SPEAK_LITE, exhausted.model)
        assertEquals(2, exhausted.line)
        assertEquals(3, speechAttempts)
        assertEquals(priorTrack.path, library.byId(video.id)?.dubbedAudioPath)
        assertArrayEquals(waveform, readSpeechWav(priorTrack).samples)
        assertEquals(listOf(priorTrack), priorTrack.parentFile?.listFiles()?.toList())
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
            Triple(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""", DubIssue.GEMINI_KEY_REJECTED),
            Triple(401, "unauthenticated", DubIssue.GEMINI_KEY_REJECTED),
            Triple(403, "access denied", DubIssue.GEMINI_AUTH),
            Triple(400, """{"error":{"status":"FAILED_PRECONDITION"}}""", DubIssue.GEMINI_PROJECT),
            Triple(402, "out of credits", DubIssue.GEMINI_PROJECT),
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

    @Test fun newAuthKeyChecksOnlyModelMetadataWithNoMediaAndReportsMissingModel() = runBlocking<Unit> {
        val authKey = "AQ.fixture_authorization.key-123"
        val newConfig = requireNotNull(GeminiDubConfig.from("  $authKey\n"))
        assertFalse(newConfig.toString().contains(authKey))
        assertNotNull(GeminiDubConfig.from("AIza-fixture_standard_key"))
        assertNull(GeminiDubConfig.from("https://example.org/key"))
        assertNull(GeminiDubConfig.from("AQ.\r\nCookie:test"))
        val checked = mutableListOf<String>()
        var missing: GeminiDubModel? = GeminiDubModel.SPEAK
        val http = geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val model = request.url.pathSegments.last()
            assertEquals("GET", request.method)
            assertEquals("generativelanguage.googleapis.com", request.url.host)
            assertEquals("https", request.url.scheme)
            assertNull(request.url.query)
            assertNull(request.body)
            assertEquals(authKey, request.header("x-goog-api-key"))
            assertNull(request.header("Cookie"))
            checked += model
            if (model == missing?.id) reply(request, 404, "not visible") else reply(request, 200,
                """{"name":"models/$model-001","baseModelId":"$model","supportedGenerationMethods":["generateContent"]}""")
        }.build()
        val api = GeminiDubApi(newConfig, http)
        assertEquals(listOf(GeminiDubModel.SPEAK), api.unavailableModels())
        assertEquals(GeminiDubModel.entries.map { it.id }, checked)
        missing = null
        assertTrue(api.unavailableModels().isEmpty())
        assertEquals(2 * GeminiDubModel.entries.size, checked.size)
    }

    @Test fun keyCheckDistinguishesRejectedKeyQuotaNetworkAndMalformedMetadata() = runBlocking<Unit> {
        val cases = listOf(401 to DubIssue.GEMINI_KEY_REJECTED, 403 to DubIssue.GEMINI_AUTH,
            429 to DubIssue.GEMINI_LIMIT, 200 to DubIssue.GEMINI_SERVICE, 0 to DubIssue.GEMINI_NETWORK)
        for ((code, issue) in cases) {
            var count = 0
            val http = geminiDubHttp.newBuilder().addInterceptor {
                count++
                if (code == 0) throw IOException("fixture private response")
                reply(it.request(), code, "{}")
            }.build()
            val error = failure { GeminiDubApi(config, http).unavailableModels() }
            assertEquals(issue, error.issue)
            assertEquals(issue.name, error.message)
            assertEquals(1, count)
        }
    }

    @Test fun explicitSpeechTestSendsOnlyOneBuiltInSentenceAndDeletesItsWav() = runBlocking<Unit> {
        var count = 0
        val http = geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            count++
            assertEquals("POST", request.method)
            assertEquals("${GeminiDubModel.SPEAK.id}:generateContent", request.url.pathSegments.last())
            val body = Buffer().also { request.body!!.writeTo(it) }
            val sent = parts(JSONObject(body.readUtf8()))
            assertEquals(1, sent.length())
            assertEquals(1, sent.getJSONObject(0).length())
            assertEquals("أهلًا، ده اختبار صوت من كادر.", sent.getJSONObject(0).getString("text"))
            reply(request, 200, ok(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(wav(DUB_SAMPLE_RATE))))))
        }.build()
        testGeminiSpeech(temp.root, GeminiDubApi(config, http))
        assertEquals(1, count)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun longAudioUsesBoundedRelativeRequestsAndStopsAtFirstQuotaFailure() = runBlocking<Unit> {
        val plan = planDub(listOf(SubCue(1, 172.0, 173.0, "original one"), SubCue(2, 185.0, 186.0, "original two"),
            SubCue(3, 390.0, 391.0, "original three")), 420.0)
        val pcm = temp.newFile("long.pcm")
        val bytesPerSecond = 32_000L
        val starts = listOf(162L, 170L, 350L)
        RandomAccessFile(pcm, "rw").use { file ->
            file.setLength(420 * bytesPerSecond)
            starts.forEachIndexed { index, start -> file.seek(start * bytesPerSecond); file.write(10 + index) }
        }
        var diarizations = 0
        var directions = 0
        var failQuota = false
        val api = GeminiDubApi(config, geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body!!.writeTo(it) }
            val json = JSONObject(buffer.readUtf8())
            val parts = parts(json)
            if (request.url.pathSegments.last().startsWith(GeminiDubModel.TRANSCRIBE.id)) {
                val i = diarizations++
                val audio = Base64.getDecoder().decode(parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
                assertTrue(audio.size <= GEMINI_PART_SECONDS * bytesPerSecond + 44)
                assertEquals(10 + i, audio[44].toInt())
                if (failQuota && i == 1) reply(request, 429, "out of quota") else reply(request, 200, when (i) {
                    0 -> ok(wordPart("a", "10.050s", "10.790s"), wordPart("a", "23.050s", "23.790s"))
                    1 -> ok(wordPart("renamed", "2.050s", "2.790s"), wordPart("renamed", "15.050s", "15.790s"))
                    else -> ok(wordPart("renamed", "40.050s", "40.790s"))
                })
            } else {
                assertTrue(request.url.pathSegments.last().startsWith(GeminiDubModel.DIRECT.id))
                directions++
                assertFalse(json.toString().contains("original one"))
                val cue = (1..3).map { """{"id":$it,"tone":"natural","reaction":"none","placement":"before"}""" }
                reply(request, 200, ok(JSONObject().put("text", """{"speakers":[],"cues":[${cue.joinToString(",")}]}""")))
            }
        }.build())
        val stages = mutableListOf<GeminiStage>()
        val direction = directGeminiAudio(pcm, plan, api) { stages += it }
        assertEquals(listOf(1, 2, 3), stages.filter { it.step == GeminiStep.ASSIGN_SPEAKERS }.map { it.part })
        assertEquals(3, diarizations)
        assertEquals(2, directions)
        assertEquals(direction.voiceFor(1), direction.voiceFor(2))
        assertNotEquals(direction.voiceFor(2), direction.voiceFor(3))
        assertTrue(direction.cast.last().unlinked)
        assertEquals(390L * DUB_SAMPLE_RATE, plan.lines.last().start)
        assertEquals("original three", plan.lines.last().text)
        diarizations = 0; directions = 0; failQuota = true
        assertEquals(DubIssue.GEMINI_LIMIT, failure { directGeminiAudio(pcm, plan, api) {} }.issue)
        assertEquals(2, diarizations)
        assertEquals(0, directions)
    }

    @Test fun partFourFallsBackWithoutReplayingTheEarlierPartsAndMarksEstimatedSpeakers() = runBlocking<Unit> {
        val plan = planDub((0..4).map { SubCue(it + 1, it * 180.0 + 5, it * 180.0 + 6, "Saved cue $it") }, 740.0)
        val pcm = temp.newFile("five-parts.pcm")
        RandomAccessFile(pcm, "rw").use { it.setLength(740 * 32_000L) }
        val routes = mutableListOf<GeminiRoutingEvent?>()
        val router = GeminiDubRouter(true, update = { routes += it }, pause = { error("Don't wait for daily quota") })
        val calls = mutableListOf<String>()
        var diarizations = 0
        var approximate = 0
        var directions = 0
        var failedAudioBytes = 0L
        val http = geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val model = request.url.pathSegments.last().substringBefore(':')
            calls += model
            assertEquals("fixture-api-key", request.header("x-goog-api-key"))
            assertNull(request.url.query)
            if (model == GeminiDubModel.TRANSCRIBE.id) {
                val i = ++diarizations
                if (i == 4) {
                    failedAudioBytes = request.body!!.contentLength()
                    reply(request, 429, quotaError("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "3"))
                } else {
                    val offset = if (i == 1) 5 else 15
                    reply(request, 200, ok(wordPart("native", "$offset.1s", "$offset.8s")))
                }
            } else {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val config = body.getJSONObject("generationConfig")
                assertFalse(config.has("audioTranscriptionConfig"))
                val schema = config.getJSONObject("responseFormat").getJSONObject("text").getJSONObject("schema")
                    .getJSONObject("properties")
                assertFalse(body.toString().contains("Saved cue"))
                if (schema.has("turns")) {
                    assertEquals(GeminiDubModel.DIRECT_LITE.id, model)
                    if (approximate++ == 0) assertTrue(request.body!!.contentLength() in failedAudioBytes..failedAudioBytes + 4096)
                    val invalid = """{"speaker":1,"from_ms":-15,"to_ms":100}"""
                    reply(request, 200, ok(JSONObject().put("text", """{"turns":[$invalid,{"speaker":1,"from_ms":15100,"to_ms":15800}]}""")))
                } else {
                    val cue = ++directions
                    if (model == GeminiDubModel.DIRECT.id) {
                        assertEquals(1, cue)
                        directions-- // Fallback is for this scene, not the first cue of the next one.
                        reply(request, 404, "fixture model unavailable")
                    } else {
                        assertEquals(GeminiDubModel.DIRECT_LITE.id, model)
                        reply(request, 200, ok(JSONObject().put("text", """{"speakers":[],"cues":[{"id":$cue,"tone":"warm","reaction":"laugh","placement":"after"}]}""")))
                    }
                }
            }
        }.build()
        val stages = mutableListOf<GeminiStage>()
        val direction = directGeminiAudio(pcm, plan, GeminiDubApi(config, http, router)) { stages += it }
        assertEquals((1..5).toList(), stages.filter { it.step == GeminiStep.ASSIGN_SPEAKERS }.map { it.part })
        assertEquals(4, diarizations)
        assertEquals(2, approximate)
        assertEquals(5, directions)
        assertEquals(List(4) { GeminiDubModel.TRANSCRIBE.id } + List(2) { GeminiDubModel.DIRECT_LITE.id } +
            GeminiDubModel.DIRECT.id + List(5) { GeminiDubModel.DIRECT_LITE.id }, calls)
        assertEquals(listOf(GeminiTask.SPEAKERS, GeminiTask.SCENE), routes.filterIsInstance<GeminiRoutingEvent.Switched>().map { it.task })
        assertEquals(listOf(4, 5), direction.matches.filterValues { it.estimated }.keys.toList())
        assertNotEquals(direction.voiceFor(4), direction.voiceFor(5))
        assertNull(direction.performance.getValue(4).reaction)
        assertEquals("Saved cue 3", direction.take(plan.lines[3], options)?.text)
        assertEquals(545L * DUB_SAMPLE_RATE, plan.lines[3].start)
    }

    @Test fun speechQuotaRetriesThenSendsTheSameTakeToLiteAndPinsItForTheNextCue() = runBlocking<Unit> {
        val take = GeminiTake("Kore", "عامل إيه؟", "warm", "ar-EG")
        val requests = mutableListOf<Pair<String, String>>()
        val waits = mutableListOf<Long>()
        var clock = 0L
        val router = GeminiDubRouter(true, pause = { waits += it; clock += it }, clock = { clock })
        val sample = wav(DUB_SAMPLE_RATE)
        val http = geminiDubHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val model = request.url.pathSegments.last().substringBefore(':')
            val json = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertEquals("fixture-api-key", request.header("x-goog-api-key"))
            requests += model to json
            if (model == GeminiDubModel.SPEAK.id) reply(request, 429, quotaError("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "30"))
                .newBuilder().header("Retry-After", "4").build()
            else reply(request, 200, ok(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(sample)))))
        }.build()
        val api = GeminiDubApi(config, http, router)
        assertArrayEquals(sample, api.synthesize(take, 4))
        assertArrayEquals(sample, api.synthesize(take.copy(text = "أنا كويس."), 5))
        assertEquals(listOf(4_250L), waits)
        assertEquals(List(2) { GeminiDubModel.SPEAK.id } + List(2) { GeminiDubModel.SPEAK_LITE.id }, requests.map { it.first })
        assertEquals(1, requests.take(3).map { it.second }.distinct().size)
        val body = JSONObject(requests[2].second)
        assertEquals(take.text, parts(body).getJSONObject(0).getString("text"))
        assertFalse(parts(body).getJSONObject(0).has("inlineData"))
        val speech = body.getJSONObject("generationConfig").getJSONObject("speechConfig")
        assertEquals("ar-EG", speech.getString("languageCode"))
        assertEquals("Kore", speech.getJSONObject("voiceConfig").getString("voice"))
    }

    @Test fun aSourceChangedDuringCooldownPreventsRetryAndFallback() = runBlocking<Unit> {
        var requests = 0
        var changed = false
        val router = GeminiDubRouter(true, pause = { changed = true })
        val http = geminiDubHttp.newBuilder().addInterceptor { chain ->
            requests++
            reply(chain.request(), 429, quotaError("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "30"))
        }.build()
        val pcm = temp.newFile().apply { writeBytes(ByteArray(4)) }
        val error = failure { GeminiDubApi(config, http, router).speakers(GeminiAudioSlice(pcm, 0, 2)) {
            if (changed) throw DubFailure(DubIssue.SOURCE_CHANGED)
        } }
        assertEquals(DubIssue.SOURCE_CHANGED, error.issue)
        assertEquals(1, requests)
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
        val job = launch { GeminiDubApi(config, factory).speakers(GeminiAudioSlice(pcm, 0, 2)) }
        withTimeout(2_000) { queued.await() }
        job.cancelAndJoin()
        assertTrue(canceled.isCompleted)
    }

    private suspend fun failure(block: suspend () -> Unit): DubFailure = try {
        block()
        throw AssertionError("Expected DubFailure")
    } catch (e: DubFailure) { e }

    private fun quotaError(quotaId: String, quotaValue: String): String = """{"error":{
        "code":429,"status":"RESOURCE_EXHAUSTED","message":"private project fixture",
        "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
            {"quotaId":"$quotaId","quotaValue":"$quotaValue","quotaDimensions":{"model":"fixture"}}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"2.125s"}]
    }}"""

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
