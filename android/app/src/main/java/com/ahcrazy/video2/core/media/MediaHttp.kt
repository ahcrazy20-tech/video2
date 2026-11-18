package com.ahcrazy.video2.core.media

import android.webkit.CookieManager
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaProblem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Cookies stay in WebView storage and are looked up for the actual URL on every HTTP hop. */
internal class MediaHttp(
    externalBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
    private val cooldowns: DownloadCooldowns = DownloadCooldowns(),
    private val cookies: (HttpUrl) -> String? = { CookieManager.getInstance().getCookie(it.toString()) }
) {
    // This guard only applies to app-owned download reads, not the player's separate requests.
    private object DownloadRead

    private fun requireNoCooldown(request: Request) {
        val owner = request.tag(DownloadAuth::class.java)?.rateLimitOwner
        cooldowns.active(owner, request.url.host)?.let { throw it.asMediaFailure() }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .addNetworkInterceptor { chain ->
            val incoming = chain.request()
            if (incoming.tag(DownloadRead::class.java) != null) requireNoCooldown(incoming)
            val request = incoming.newBuilder().removeHeader("Cookie")
            val auth = incoming.tag(DownloadAuth::class.java)
            if (auth != null) {
                request.removeHeader("Origin").removeHeader("Referer").removeHeader("Accept")
                val original = chain.call().request().url
                val downgrade = !incoming.url.isHttps && (original.isHttps || httpMediaUrl(auth.referer)?.isHttps == true)
                auth.headersFor(incoming.url, original).forEach { (name, value) -> request.header(name, value) }
                if (auth.useBrowserCookies && !auth.externalHttpsOnly && !downgrade) {
                    cookies(incoming.url)?.safeHeader(32_768)?.let { request.header("Cookie", it) }
                }
            }
            chain.proceed(request.build())
        }.build()

    fun request(url: HttpUrl, auth: DownloadAuth): Request.Builder = Request.Builder().url(url)
        .tag(DownloadAuth::class.java, auth)

    fun playerClient(auth: DownloadAuth): OkHttpClient = (if (auth.externalHttpsOnly) external else client).newBuilder().addInterceptor { chain ->
        val request = chain.request().newBuilder().tag(DownloadAuth::class.java, auth)
        chain.proceed(request.build())
    }.build()

    private val external = externalBuilder.externalTransport().apply {
        interceptors().add(0, PublicMediaReads)
        // PublicMediaReads follows redirects explicitly. Check each target before it connects.
        interceptors().add(1, Interceptor { chain ->
            val request = chain.request()
            if (request.tag(DownloadRead::class.java) != null) requireNoCooldown(request)
            chain.proceed(request)
        })
    }
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 }).build()

    suspend fun <T> read(request: Request, consume: suspend (Response) -> T): T {
        val transport = if (request.tag(DownloadAuth::class.java)?.externalHttpsOnly == true) external else client
        val host = request.url.host
        val owner = request.tag(DownloadAuth::class.java)?.rateLimitOwner
        requireNoCooldown(request)
        return transport.readMedia(request.newBuilder().tag(DownloadRead::class.java, DownloadRead).build()) { response ->
            if (response.code == 429) {
                val actual = response.request.url.host
                val pause = cooldowns.hold(owner ?: host, actual, retryAfterMillis(response.header("Retry-After")))
                if (owner != null && actual != host) cooldowns.hold(host, actual, null, pause.untilMs)
                throw pause.asMediaFailure()
            }
            consume(response)
        }
    }
}

/** Validate each URL before connecting, including redirects for playlists, segments and ranged resumes. */
internal object PublicMediaReads : Interceptor {
    private val redirects = setOf(301, 302, 303, 307, 308)

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request().newBuilder()
            .removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization")
            .removeHeader("Referer").removeHeader("Origin").removeHeader("Accept")
            .removeHeader("Host").header("User-Agent", DownloadAuth.CHROME_UA).build()
        val seen = hashSetOf<HttpUrl>()
        repeat(6) { hop ->
            if (externalHttpsUrl(request.url.toString()) == null || !seen.add(request.url)) throw invalidTarget()
            val response = chain.proceed(request)
            if (response.code !in redirects) return response
            val next = response.use { it.header("Location")?.let(it.request.url::resolve) } ?: throw invalidTarget()
            if (hop == 5) throw invalidTarget()
            request = request.newBuilder().url(next).build()
        }
        throw invalidTarget()
    }

    private fun invalidTarget() = MediaDownloadException(MediaProblem.NOT_MEDIA.ar, MediaProblem.NOT_MEDIA)
}

// Keep the cancellation watcher alive until the response body has been consumed, not just until headers arrive.
internal suspend fun <T> OkHttpClient.readMedia(request: Request, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val call = newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            ensureActive()
            call.execute().use { consume(it) }
        } finally { watcher.cancel() }
    }
}

internal fun Response.requireMediaSuccess() {
    if (isSuccessful) return
    val problem = when (code) {
        401 -> MediaProblem.SIGN_IN
        403 -> MediaProblem.ACCESS
        404, 410 -> MediaProblem.EXPIRED
        408, 425, 429, 500, 502, 503, 504 -> MediaProblem.TEMPORARY
        else -> MediaProblem.BAD_RESPONSE
    }
    throw MediaDownloadException("${problem.ar} (HTTP $code)", problem, code, retryAfterMillis(header("Retry-After")), request.url.host)
}

/** Only retry safe reads on transient responses. Permanent access failures are never retried. */
internal suspend fun <T> retryMediaRead(read: suspend () -> T): T {
    repeat(2) { attempt ->
        try { return read() } catch (e: java.io.IOException) {
            val wait = when (e) {
                is MediaDownloadException -> if (e.problem == MediaProblem.TEMPORARY && e.status != 429) e.retryDelayMillis ?: (350L shl attempt) else null
                is SocketException, is SocketTimeoutException, is EOFException, is UnknownHostException -> 350L shl attempt
                else -> null
            }
            if (wait == null || wait > 3_000) throw e
            delay(wait.coerceAtLeast(150))
        }
    }
    return read()
}

/** A single plain GET is allowed when the optional byte-range form is refused. */
internal fun rangeNotAccepted(code: Int): Boolean = code in setOf(400, 403, 405, 412, 416, 501)

internal fun Response.mediaValidator(): String? = header("ETag")?.takeUnless { it.startsWith("W/", true) || it.isBlank() }
    ?: header("Last-Modified")?.takeIf { it.isNotBlank() }
