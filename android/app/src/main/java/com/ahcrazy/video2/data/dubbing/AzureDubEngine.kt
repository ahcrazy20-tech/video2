package com.ahcrazy.video2.data.dubbing

import com.ahcrazy.video2.core.dubbing.DUB_SAMPLE_RATE
import com.ahcrazy.video2.core.dubbing.DubFailure
import com.ahcrazy.video2.core.dubbing.DubIssue
import com.ahcrazy.video2.core.dubbing.DubLine
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.SpeechPcmHint
import com.ahcrazy.video2.core.dubbing.azureDubSsml
import com.ahcrazy.video2.core.dubbing.azureSpeechEndpoint
import com.ahcrazy.video2.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_SSML_BYTES = 64 * 1024
private const val MAX_WAV_BYTES = 2L * DUB_SAMPLE_RATE * 90 + 64 * 1024
private val SSML = "application/ssml+xml".toMediaType()

/** Only explicit dub actions construct a request. No cookies, redirects, shared media headers or retries. */
private val azureHttp by lazy {
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
}

// Not a data class: credentials must not appear in a generated toString().
internal class AzureDubConfig private constructor(private val endpoint: String, private val key: String) {
    fun request(voice: DubVoice, line: DubLine): Request {
        if (line.text.length > MAX_SSML_BYTES) throw DubFailure(DubIssue.TOO_LARGE, line.number)
        val payload = azureDubSsml(voice, line.text).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SSML_BYTES) throw DubFailure(DubIssue.TOO_LARGE, line.number)
        return Request.Builder().url(endpoint)
            .header("Ocp-Apim-Subscription-Key", key)
            .header("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
            .header("User-Agent", "Kadr-Android")
            .post(payload.toRequestBody(SSML)).build()
    }

    companion object {
        fun from(key: String?, region: String): AzureDubConfig? {
            val secret = key?.trim()?.takeIf { it.length in 1..256 && it.all { c -> c in '!'..'~' } } ?: return null
            return AzureDubConfig(azureSpeechEndpoint(region) ?: return null, secret)
        }
    }
}

internal suspend fun loadAzureDubConfig(settings: AppSettings): AzureDubConfig = withContext(Dispatchers.IO) {
    try {
        AzureDubConfig.from(settings.getKey("azure"), settings.azureRegion.first()) ?: throw DubFailure(DubIssue.AZURE_SETUP)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { throw DubFailure(DubIssue.AZURE_SETUP) }
}

internal class AzureDubEngine(private val config: AzureDubConfig, private val http: Call.Factory = azureHttp) {
    suspend fun synthesize(voice: DubVoice, line: DubLine, output: File): SpeechPcmHint {
        currentCoroutineContext().ensureActive()
        val request = config.request(voice, line)
        val wav = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(DubFailure(DubIssue.AZURE_NETWORK, line.number))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!continuation.isActive) return
                            it.readAudio(line.number)
                        }
                        continuation.resume(bytes)
                    } catch (error: DubFailure) {
                        continuation.resumeWithException(error)
                    } catch (_: IOException) {
                        continuation.resumeWithException(DubFailure(DubIssue.AZURE_NETWORK, line.number))
                    }
                }
            })
        }
        currentCoroutineContext().ensureActive()
        output.writeBytes(wav)
        return SpeechPcmHint.AZURE_PCM16
    }
}

private fun Response.readAudio(line: Int): ByteArray {
    if (code != 200) throw DubFailure(when (code) {
        401, 403 -> DubIssue.AZURE_AUTH
        429 -> DubIssue.AZURE_LIMIT
        else -> DubIssue.AZURE_SERVICE
    }, line)
    val audio = body ?: throw DubFailure(DubIssue.AUDIO_FORMAT, line)
    val source = audio.source()
    if (audio.contentLength() > MAX_WAV_BYTES || source.request(MAX_WAV_BYTES + 1)) {
        throw DubFailure(DubIssue.AUDIO_FORMAT, line)
    }
    return source.readByteArray()
}
