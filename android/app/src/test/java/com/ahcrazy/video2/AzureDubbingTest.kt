package com.ahcrazy.video2

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubLine
import com.ahcrazy.video2.core.dubbing.DubProvider
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import com.ahcrazy.video2.core.dubbing.azureDubSsml
import com.ahcrazy.video2.core.dubbing.azureDubVoices
import com.ahcrazy.video2.core.dubbing.azureSpeechEndpoint
import com.ahcrazy.video2.core.dubbing.readSpeechWav
import com.ahcrazy.video2.data.dubbing.AzureDubConfig
import com.ahcrazy.video2.data.dubbing.AzureDubEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

class AzureDubbingTest {
    @get:Rule val temp = TemporaryFolder()
    private val voice = azureDubVoices.voices.first { it.id == "ar-EG-ShakirNeural" }
    private val line = DubLine(7, 0, 3L * DUB_SAMPLE_RATE, "أهلًا بيك & صباح الخير")
    private val config = AzureDubConfig.from("fixture-key", " EastUS ")!!

    @Test fun catalogIsExplicitAndEscapesSubtitleTextWithoutInterpretingSsml() {
        assertEquals(listOf("ar-EG", "ar-SA"), azureDubVoices.dialects("ar"))
        assertEquals("ar-EG", azureDubVoices.initialDialect("ar"))
        assertEquals("ar-SA", azureDubVoices.initialDialect("ar-SA"))
        assertNull(azureDubVoices.resolve("ar-EG", voice.id, allowNetwork = false))
        assertNull(azureDubVoices.resolve("ar-SA", voice.id, allowNetwork = true))
        assertEquals(voice, azureDubVoices.resolve("ar-EG", voice.id, allowNetwork = true))
        val text = "أهلًا & <audio src='https://example.invalid/'> \"خبر\" 😀"
        val ssml = azureDubSsml(voice, "$text\u0000\uD800")
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ssml.byteInputStream())
        assertEquals("ar-EG", xml.documentElement.getAttribute("xml:lang"))
        assertEquals(1, xml.getElementsByTagName("voice").length)
        assertEquals(voice.id, xml.getElementsByTagName("voice").item(0).attributes.getNamedItem("name").nodeValue)
        assertEquals(0, xml.getElementsByTagName("audio").length)
        assertEquals(text, xml.documentElement.textContent)
        val device = voice.copy(provider = DubProvider.ANDROID)
        assertEquals(DubIssue.VOICE_CHANGED, assertThrows(DubFailure::class.java) { azureDubSsml(device, text) }.issue)
    }

    @Test fun regionAndKeyAreRequiredWithoutAnArbitraryEndpoint() {
        assertEquals("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1", azureSpeechEndpoint(" EastUS "))
        assertEquals("https://usgovvirginia.tts.speech.azure.us/cognitiveservices/v1", azureSpeechEndpoint("usgovvirginia"))
        for (region in listOf("", "https://elsewhere.example", "eastus.evil.example", "eastus@elsewhere", "eastus/x", "eastus\nwestus")) {
            assertNull(azureSpeechEndpoint(region))
        }
        assertNull(AzureDubConfig.from(null, "eastus"))
        assertNull(AzureDubConfig.from("a\nb", "eastus"))
        assertFalse(config.toString().contains("fixture-key"))
        val tooLong = line.copy(text = "&".repeat(16_000))
        assertEquals(DubIssue.TOO_LARGE, assertThrows(DubFailure::class.java) { config.request(voice, tooLong) }.issue)
    }

    @Test fun restPostsOnlyEscapedTextAndReturnsReadablePcm() = runBlocking<Unit> {
        val expected = shortArrayOf(-200, 7, 1032, -32)
        for (streamed in listOf(false, true)) {
            val engine = AzureDubEngine(config, OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("POST", request.method)
                assertEquals("eastus.tts.speech.microsoft.com", request.url.host)
                assertEquals("/cognitiveservices/v1", request.url.encodedPath)
                assertEquals("fixture-key", request.header("Ocp-Apim-Subscription-Key"))
                assertEquals("riff-24khz-16bit-mono-pcm", request.header("X-Microsoft-OutputFormat"))
                assertNull(request.header("Cookie"))
                assertNull(request.header("Referer"))
                assertEquals("application/ssml+xml", request.body!!.contentType().toString())
                assertEquals(azureDubSsml(voice, line.text), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                reply(request, 200, wav(expected, streamed = streamed))
            }.build())
            val output = temp.newFile()
            val hint = engine.synthesize(voice, line, output)
            assertEquals(SpeechPcmHint.AZURE_PCM16, hint)
            val pcm = readSpeechWav(output, hint)
            assertEquals(DUB_SAMPLE_RATE, pcm.rate)
            assertArrayEquals(expected, pcm.samples)
        }
    }

    @Test fun providerFailuresAreSafeAndInvalidAudioDoesNotParse() = runBlocking<Unit> {
        val output = temp.newFile()
        for ((status, issue) in mapOf(401 to DubIssue.AZURE_AUTH, 403 to DubIssue.AZURE_AUTH, 429 to DubIssue.AZURE_LIMIT,
            302 to DubIssue.AZURE_SERVICE, 503 to DubIssue.AZURE_SERVICE)) {
            val engine = AzureDubEngine(config, OkHttpClient.Builder().addInterceptor { chain ->
                reply(chain.request(), status, "do not display this body".toByteArray())
            }.build())
            try {
                engine.synthesize(voice, line, output)
                fail("Expected a provider failure")
            } catch (error: DubFailure) {
                assertEquals(issue, error.issue)
                assertEquals(line.number, error.line)
                assertEquals(issue.name, error.message)
                assertEquals(0L, output.length())
            }
        }
        for (bad in listOf("<html>not audio</html>".toByteArray(), wav(shortArrayOf(5), rate = 16_000), wav(shortArrayOf(5)).dropLast(1).toByteArray())) {
            output.writeBytes(bad)
            assertEquals(DubIssue.AUDIO_FORMAT,
                assertThrows(DubFailure::class.java) { readSpeechWav(output, SpeechPcmHint.AZURE_PCM16) }.issue)
        }
    }

    @Test fun cancellingARequestCancelsItsCallAndNeverWritesTheCue() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val transport = object : Call.Factory {
            override fun newCall(request: Request): Call = object : Call {
                override fun request() = request
                override fun execute(): Response = throw IOException("not used")
                override fun enqueue(responseCallback: Callback) { started.complete(Unit) }
                override fun cancel() { cancelled.complete(Unit) }
                override fun isExecuted() = started.isCompleted
                override fun isCanceled() = cancelled.isCompleted
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = newCall(request)
            }
        }
        val output = temp.newFile()
        val job = launch { AzureDubEngine(config, transport).synthesize(voice, line, output) }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        assertEquals(0L, output.length())
    }

    private fun reply(request: Request, code: Int, body: ByteArray) = Response.Builder()
        .protocol(Protocol.HTTP_1_1).request(request).code(code).message("fixture").body(body.toResponseBody()).build()

    private fun wav(samples: ShortArray, streamed: Boolean = false, rate: Int = DUB_SAMPLE_RATE): ByteArray {
        val bytes = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(if (streamed) -1 else 36 + samples.size * 2)
        bytes.put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate)
        bytes.putInt(2 * rate).putShort(2).putShort(16).put("data".toByteArray()).putInt(if (streamed) -1 else samples.size * 2)
        samples.forEach { bytes.putShort(it) }
        return bytes.array()
    }
}
