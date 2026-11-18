package com.ahcrazy.video2.core.media

import android.webkit.CookieManager
import com.ahcrazy.video2.core.model.DownloadAuth
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Cookies stay in WebView storage and are looked up for the actual URL on every HTTP hop. */
internal class MediaHttp(private val cookies: (HttpUrl) -> String? = { CookieManager.getInstance().getCookie(it.toString()) }) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .addNetworkInterceptor { chain ->
            val incoming = chain.request()
            val request = incoming.newBuilder().removeHeader("Cookie")
            val auth = incoming.tag(DownloadAuth::class.java)
            if (auth != null) {
                val downgrade = !incoming.url.isHttps && httpMediaUrl(auth.referer)?.isHttps == true
                if (downgrade) request.removeHeader("Referer").removeHeader("Origin")
                if (auth.useBrowserCookies && !downgrade) {
                    cookies(incoming.url)?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
                }
            }
            chain.proceed(request.build())
        }.build()

    fun request(url: HttpUrl, auth: DownloadAuth): Request.Builder = Request.Builder().url(url)
        .tag(DownloadAuth::class.java, auth).also { b -> auth.headers().forEach { (k,v) -> b.header(k, v) } }

    fun playerClient(auth: DownloadAuth): OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val request = chain.request().newBuilder().tag(DownloadAuth::class.java, auth)
        auth.headers().forEach { (k, v) -> request.header(k, v) }
        chain.proceed(request.build())
    }.build()

    // Keep the cancellation watcher alive until the response body has been consumed, not just until headers arrive.
    suspend fun <T> read(request: Request, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = client.newCall(request)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                ensureActive()
                call.execute().use { consume(it) }
            } finally { watcher.cancel() }
        }
    }
}

internal fun Response.requireMediaSuccess() {
    if (isSuccessful) return
    throw MediaDownloadException(when (code) {
        401, 403 -> "المصدر رفض التحميل (HTTP $code). افتح الصفحة وسجّل الدخول أو شغّل الفيديو والتقط رابطًا جديدًا."
        404, 410 -> "الرابط غير متاح أو انتهت صلاحيته (HTTP $code). التقط رابطًا جديدًا من الصفحة."
        else -> "تعذّر تنزيل المصدر (HTTP $code)."
    })
}
