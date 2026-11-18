package com.ahcrazy.video2.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.isVideoAd
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class BrowserSession {
    val tabs = mutableStateListOf(BrowserTab())
    var currentId by mutableStateOf(tabs.first().id)
    val current: BrowserTab get() = tabs.first { it.id == currentId }
    @Volatile var adBlock = true

    fun add() { BrowserTab().also { tabs.add(it); currentId = it.id } }
    fun close(tab: BrowserTab) {
        tabs.remove(tab)
        if (tabs.isEmpty()) add() else if (currentId == tab.id) currentId = tabs.first().id
        tab.destroy()
    }
    fun destroy() { tabs.forEach { it.destroy() } }
}

/** The WebView and all callbacks belong to this tab, independent of Compose selecting another tab. */
internal class BrowserTab(val id: String = UUID.randomUUID().toString()) {
    var title by mutableStateOf("تبويب جديد")
    var url by mutableStateOf("")
        private set
    var address by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var loading by mutableStateOf(false)
    val detected = mutableStateListOf<DetectedMedia>()
    var drm by mutableStateOf(DRMKind.NONE)
        private set
    var hideDrmBanner by mutableStateOf(false)
    var webView: WebView? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private val framesWithDrm = mutableSetOf<String>()
    private val urlsWithDrm = mutableMapOf<String, DRMKind>()
    private val nativeRequests = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pageEpoch = 0
    private var destroyed = false

    fun open(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val search = { "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8") }
        val candidate = httpMediaUrl(input) ?: if (input.contains("://")) return
            else if (input.contains('.') && input.none(Char::isWhitespace)) httpMediaUrl("https://$input") else httpMediaUrl(search())
        candidate ?: return
        val target = candidate.toString()
        address = target
        url = target
        webView?.loadUrl(target)
    }

    fun auth(media: DetectedMedia? = null) = DownloadAuth(
        userAgent = webView?.settings?.userAgentString ?: DownloadAuth.CHROME_UA,
        referer = media?.requestReferer ?: url.ifBlank { null },
        origin = media?.requestOrigin,
        useBrowserCookies = true
    )

    fun rescan() { webView?.evaluateJavascript("window.__v2Scan && window.__v2Scan()", null) }

    fun ingest(incoming: DetectedMedia) {
        if (destroyed || incoming.isFragment) return
        val oldIndex = detected.indexOfFirst { it.url == incoming.url }
        val old = detected.getOrNull(oldIndex)
        val protection = old?.drm?.takeIf { it.isProtected } ?: urlsWithDrm[incoming.url] ?: incoming.drm.takeIf { it.isProtected }
            ?: if (incoming.requestReferer?.let { it in framesWithDrm } == true) DRMKind.UNKNOWN_PROTECTED else DRMKind.NONE
        if (protection.isProtected) {
            drm = protection
            if (urlsWithDrm.size < 200) urlsWithDrm[incoming.url] = protection
        }
        val next = if (old == null) incoming.copy(drm = protection) else old.copy(
            title = incoming.title.takeIf { incoming.extractionMethod.startsWith("html5") && !old.extractionMethod.startsWith("html5") } ?: old.title,
            kind = incoming.kind.takeIf { it != MediaKind.OTHER && (incoming.mime != null || old.kind == MediaKind.OTHER || incoming.probed) } ?: old.kind,
            mime = incoming.mime ?: old.mime, drm = protection,
            extractionMethod = incoming.extractionMethod.takeIf { it.startsWith("html5") } ?: old.extractionMethod,
            durationSec = incoming.durationSec ?: old.durationSec, width = incoming.width ?: old.width, height = incoming.height ?: old.height,
            byteSize = incoming.byteSize ?: old.byteSize, variants = incoming.variants.ifEmpty { old.variants }, probed = old.probed || incoming.probed,
            requestReferer = incoming.requestReferer?.takeIf { old.requestReferer == null || (incoming.extractionMethod == "webview-request") } ?: old.requestReferer,
            requestOrigin = incoming.requestOrigin ?: old.requestOrigin,
            fromAd = old.fromAd || incoming.fromAd
        )
        if (old == next) return
        if (oldIndex >= 0) detected[oldIndex] = next else {
            if (detected.size >= 150) {
                val lowest = detected.indices.minByOrNull { mediaScore(detected[it]) } ?: return
                if (mediaScore(detected[lowest]) >= mediaScore(next)) return
                detected.removeAt(lowest)
            }
            detected.add(next)
        }
    }

    private fun startPage(target: String) {
        pageEpoch++
        url = target
        address = target
        loading = true
        progress = 0f
        detected.clear()
        drm = DRMKind.NONE
        hideDrmBanner = false
        framesWithDrm.clear()
        urlsWithDrm.clear()
        nativeRequests.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context, script: String, adHosts: Set<String>, session: BrowserSession): WebView {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.onResume()
            return it
        }
        val view = WebView(context)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        val bridge = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (bridge) {
            // This listener only accepts bounded observation data. It has no download or privileged command surface.
            WebViewCompat.addWebMessageListener(view, "Video2Media", setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                val origin = httpMediaUrl(sourceOrigin.toString()) ?: return@addWebMessageListener
                if (isMainFrame && httpMediaUrl(view.url)?.origin() != origin.origin()) return@addWebMessageListener
                message.data?.let { ingestMessage(it, origin) }
            }
        }
        if (early) WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress / 100f }
            override fun onReceivedTitle(view: WebView?, newTitle: String?) { if (!newTitle.isNullOrBlank()) title = newTitle }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && httpMediaUrl(request.url.toString()) == null

            override fun onPageStarted(view: WebView, target: String?, favicon: Bitmap?) { startPage(target.orEmpty()) }

            override fun onPageFinished(view: WebView, target: String?) {
                if (target != view.url || destroyed) return
                loading = false
                if (httpMediaUrl(target) != null) {
                    if (!early) view.evaluateJavascript(script, null)
                    else rescan()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, target: String?, isReload: Boolean) {
                if (target == null || target == url) return
                val wasEditing = address != url
                url = target
                if (!wasEditing) address = target
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loading = false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val target = httpMediaUrl(request.url.toString()) ?: return null
                val adHost = generateSequence(target.host) { h -> h.substringAfter('.', "").ifEmpty { null } }.any { it in adHosts }
                if (session.adBlock && adHost && !request.isForMainFrame) return WebResourceResponse("text/plain", "utf-8", byteArrayOf().inputStream())
                if (request.method != "GET" || target.isMediaFragment()) return null
                val header = request.requestHeaders
                val accept = header.header("Accept")?.substringBefore(',')?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                val kind = MediaKind.infer(target.toString(), accept)
                if (kind == MediaKind.OTHER || kind == MediaKind.TS) return null
                val ref = httpMediaUrl(header.header("Referer"))
                val origin = httpMediaUrl(header.header("Origin"))
                val epoch = pageEpoch
                if (nativeRequests.size < 450 && nativeRequests.add("$epoch|$target|${ref.orEmptyString()}")) {
                    main.post {
                        if (!destroyed && epoch == pageEpoch) candidate(target, kind, accept, "webview-request", ref, origin, ad = adHost)
                    }
                }
                return null // Do not proxy or execute a second request.
            }
        }
        view.setDownloadListener { target, _, disposition, mime, length ->
            val remote = httpMediaUrl(target) ?: return@setDownloadListener
            val filename = disposition?.let { android.webkit.URLUtil.guessFileName(target, it, mime) }
            val kind = MediaKind.infer(target, mime).takeIf { it != MediaKind.OTHER } ?: MediaKind.infer(filename.orEmpty(), mime)
            if (kind == MediaKind.OTHER || kind == MediaKind.TS) return@setDownloadListener
            candidate(remote, kind, mime, "webview-download", httpMediaUrl(view.url), null, label = filename, size = length.takeIf { it > 0 })
        }
        if (!bridge) main.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed) return
                val origin = httpMediaUrl(view.url)
                if (view.isAttachedToWindow && origin != null) {
                    val epoch = pageEpoch
                    view.evaluateJavascript("window.__v2Drain ? window.__v2Drain() : []") { result ->
                        if (!destroyed && epoch == pageEpoch && result.length <= 600_000) runCatching {
                            val arr = JSONArray(result)
                            for (i in 0 until minOf(80, arr.length())) ingestMessage(arr.optString(i), origin)
                        }
                    }
                }
                main.postDelayed(this, 1600)
            }
        }, 1600)
        if (url.isNotBlank()) view.loadUrl(url)
        return view
    }

    private fun ingestMessage(payload: String, origin: HttpUrl) {
        if (destroyed || payload.length !in 1..24_000) return
        val body = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val current = httpMediaUrl(url) ?: return
        val frame = httpMediaUrl(body.optString("page"))?.takeIf { it.origin() == origin.origin() } ?: return
        if (body.optString("type") == "drm") {
            val media = httpMediaUrl(body.optString("url"))?.toString()
            drm = DRMKind.UNKNOWN_PROTECTED
            if (media != null && urlsWithDrm.size < 200) urlsWithDrm[media] = drm
            if (media == null && framesWithDrm.size < 40) framesWithDrm.add(frame.toString())
            for (i in detected.indices) {
                val item = detected[i]
                if (!item.drm.isProtected && (item.url == media || (media == null && item.requestReferer == frame.toString()))) {
                    detected[i] = item.copy(drm = drm)
                }
            }
            return
        }
        if (body.optString("type") != "media") return
        val item = body.optJSONObject("item") ?: return
        val target = httpMediaUrl(item.optString("url")) ?: return
        val type = item.optString("mime").take(150).takeIf { it.isNotBlank() }
        val method = item.optString("method", "page").takeIf { it.matches(Regex("[a-z0-9-]{1,32}")) } ?: "page"
        val kind = MediaKind.infer(target.toString(), type)
        if (kind == MediaKind.TS) return
        val protection = runCatching { DRMKind.valueOf(item.optString("drm")) }.getOrDefault(DRMKind.NONE)
        ingest(DetectedMedia(
            url = target.toString(), title = body.optString("title").take(512).ifBlank { titleFor(target) }, kind = kind, mime = type,
            drm = protection, pageUrl = current.toString(), extractionMethod = method,
            durationSec = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            width = item.optInt("width").takeIf { it in 1..16384 }, height = item.optInt("height").takeIf { it in 1..16384 },
            requestReferer = frame.toString(), requestOrigin = origin.origin(), fromAd = item.optBoolean("ad") || target.isVideoAd()
        ))
    }

    private fun candidate(target: HttpUrl, kind: MediaKind, mime: String?, method: String, ref: HttpUrl?, origin: HttpUrl?, ad: Boolean = false, label: String? = null, size: Long? = null) {
        if (httpMediaUrl(url) == null) return
        ingest(DetectedMedia(target.toString(), label?.take(250)?.ifBlank { titleFor(target) } ?: titleFor(target), kind,
            mime = mime, pageUrl = url, extractionMethod = method, byteSize = size,
            requestReferer = ref?.toString(), requestOrigin = origin?.origin(), fromAd = ad || target.isVideoAd()))
    }

    private fun titleFor(url: HttpUrl) = title.takeUnless { it == "تبويب جديد" || it.isBlank() }
        ?: url.pathSegments.last().ifBlank { url.host }

    fun destroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    private fun Map<String, String>.header(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value
    private fun HttpUrl?.orEmptyString() = this?.toString().orEmpty()
}

internal fun mediaScore(item: DetectedMedia): Int =
    (if (item.kind.isCompleteVideo) 50 else 0) + (if (item.kind.exoSupported) 15 else 0) +
        (if ((item.durationSec ?: 0.0) > 5.0) 18 else 0) + (if (item.extractionMethod.startsWith("html5")) 22 else 0) +
        minOf((item.height ?: 0) / 32, 40) + (if (item.drm.isProtected) -100 else 0) + (if (item.fromAd) -100 else 0)

internal data class BrowserStream(val media: DetectedMedia, val auth: DownloadAuth)
