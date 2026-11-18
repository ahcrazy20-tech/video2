package com.ahcrazy.video2.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.MediaRequestContexts
import com.ahcrazy.video2.core.media.ObservedMediaRequest
import com.ahcrazy.video2.core.media.pageReferer
import com.ahcrazy.video2.core.media.safeHeader
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.isPlayerTrackDelivery
import com.ahcrazy.video2.core.media.isVideoAd
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

internal class BrowserSession {
    val tabs = mutableStateListOf(BrowserTab())
    var currentId by mutableStateOf(tabs.first().id)
    val current: BrowserTab get() = tabs.first { it.id == currentId }
    @Volatile var adBlock = true

    fun add() { BrowserTab().also { tabs.add(it); currentId = it.id } }

    /** Keep the actual watch page and its session when returning from an unavailable download. */
    fun reopenSource(page: String): BrowserTab? {
        val target = httpMediaUrl(page) ?: return null
        val retained = current.takeIf { httpMediaUrl(it.url) == target } ?: tabs.firstOrNull { httpMediaUrl(it.url) == target }
        if (retained != null) { currentId = retained.id; return retained }
        return current.takeIf { it.open(target.toString()) }
    }
    fun close(tab: BrowserTab) {
        tabs.remove(tab)
        if (tabs.isEmpty()) add() else if (currentId == tab.id) currentId = tabs.first().id
        tab.destroy()
    }
    fun destroy() { tabs.forEach { it.destroy() } }
}

internal enum class BrowserPageError { LOAD_FAILED, WEBVIEW_UNAVAILABLE, RENDERER_STOPPED }
internal enum class PlaybackHint { PLAYER, IN_PAGE, SEGMENTED }
internal data class WebFullscreen(val view: View, val callback: WebChromeClient.CustomViewCallback)

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
    var playbackHint by mutableStateOf<PlaybackHint?>(null)
        private set
    var captureRevision by mutableStateOf(0)
        private set
    var fullscreen by mutableStateOf<WebFullscreen?>(null)
        private set
    var failure by mutableStateOf<BrowserPageError?>(null)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    @Volatile private var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val framesWithDrm = mutableSetOf<String>()
    private val urlsWithDrm = mutableMapOf<String, DRMKind>()
    private val requestContexts = MediaRequestContexts()
    private val downloadUserAgents = mutableMapOf<HttpUrl, String>()
    @Volatile private var pageEpoch = 0
    @Volatile private var lastTrackSignalEpoch = -1
    private var destroyed = false

    fun open(raw: String): Boolean {
        val target = browserTarget(raw) ?: return false
        startPage(target)
        webView?.loadUrl(target)
        return true
    }

    fun goBack() { webView?.takeIf { it.canGoBack() }?.goBack() }

    fun goForward() { webView?.takeIf { it.canGoForward() }?.goForward() }

    fun reload() {
        if (url.isBlank()) return
        if (failure != null || webView == null) open(url) else webView?.reload()
    }

    fun goHome() {
        startPage("")
        loading = false
        title = "تبويب جديد"
        disposeWebView()
    }

    fun auth(media: DetectedMedia? = null, targetUrl: String? = media?.url): DownloadAuth {
        val target = httpMediaUrl(targetUrl)
        val ua = target?.let(downloadUserAgents::get) ?: webView?.settings?.userAgentString ?: DownloadAuth.CHROME_UA
        if (target != null) requestContexts.get(target)?.let { return it.authFor(target, ua) }
        return DownloadAuth(ua, referer = pageReferer(httpMediaUrl(media?.requestReferer ?: url), target), useBrowserCookies = true)
    }

    fun noteDownloadFailure(target: String, problem: MediaProblem?, status: Int?) {
        if (problem?.needsNewLink != true) return
        val i = detected.indexOfFirst { it.url == target }
        if (i >= 0) detected[i] = detected[i].copy(probed = true, probeProblem = problem, checkedAtMs = null, probeHttpStatus = status, probeRetryAtMs = null, probeHost = null)
    }

    fun rescan() { webView?.evaluateJavascript("window.__v2Scan && window.__v2Scan()", null) }

    fun exitFullscreen() {
        val active = fullscreen ?: return
        fullscreen = null
        (active.view.parent as? ViewGroup)?.removeView(active.view)
        active.callback.onCustomViewHidden()
    }

    private fun notePlayback(hint: PlaybackHint) {
        if (hint.ordinal > (playbackHint?.ordinal ?: -1)) playbackHint = hint
    }

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
            title = incoming.title.takeIf { incoming.extractionMethod.startsWith("player-") ||
                (incoming.extractionMethod.startsWith("html5") && !old.extractionMethod.startsWith("player-") && !old.extractionMethod.startsWith("html5")) } ?: old.title,
            kind = incoming.kind.takeIf { it != MediaKind.OTHER && (incoming.mime != null || old.kind == MediaKind.OTHER || incoming.probed) } ?: old.kind,
            mime = incoming.mime ?: old.mime, drm = protection,
            extractionMethod = when {
                incoming.extractionMethod == "webview-download" -> incoming.extractionMethod
                old.extractionMethod == "webview-download" -> old.extractionMethod
                incoming.extractionMethod.startsWith("html5") || incoming.extractionMethod.startsWith("player-") -> incoming.extractionMethod
                else -> old.extractionMethod
            },
            qualityLabel = incoming.qualityLabel ?: old.qualityLabel,
            durationSec = incoming.durationSec ?: old.durationSec, width = incoming.width ?: old.width, height = incoming.height ?: old.height,
            byteSize = incoming.byteSize ?: old.byteSize, variants = incoming.variants.ifEmpty { old.variants }, probed = old.probed || incoming.probed,
            requestReferer = incoming.requestReferer?.takeIf { old.requestReferer == null || (incoming.extractionMethod == "webview-request") } ?: old.requestReferer,
            fromAd = old.fromAd || incoming.fromAd,
            probeProblem = if (incoming.probed) incoming.probeProblem else old.probeProblem,
            checkedAtMs = if (incoming.probed) incoming.checkedAtMs else old.checkedAtMs,
            probeHttpStatus = if (incoming.probed) incoming.probeHttpStatus else old.probeHttpStatus,
            probeRetryAtMs = if (incoming.probed) incoming.probeRetryAtMs else old.probeRetryAtMs,
            probeHost = if (incoming.probed) incoming.probeHost else old.probeHost,
            checkedVariantUrl = if (incoming.probed) incoming.checkedVariantUrl else old.checkedVariantUrl,
            checkedAudioGroup = if (incoming.probed) incoming.checkedAudioGroup else old.checkedAudioGroup,
            publicPageUrl = incoming.publicPageUrl ?: old.publicPageUrl
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

    private fun resetCapture() {
        pageEpoch++
        captureRevision = pageEpoch
        detected.clear()
        drm = DRMKind.NONE
        playbackHint = null
        framesWithDrm.clear()
        urlsWithDrm.clear()
        requestContexts.reset(pageEpoch)
        downloadUserAgents.clear()
    }

    private fun startPage(target: String) {
        exitFullscreen()
        resetCapture()
        url = target
        address = target
        failure = null
        loading = true
        progress = 0f
    }

    private fun updateRoute(target: String) {
        if (httpMediaUrl(target) == httpMediaUrl(url)) return
        val wasEditing = address != url
        resetCapture()
        url = target
        if (!wasEditing) address = target
    }

    /** Attach the retained WebView inside a clipped native container for this viewport only. */
    fun mount(container: FrameLayout, script: String, adHosts: Set<String>, session: BrowserSession) {
        if (destroyed || url.isBlank() || failure == BrowserPageError.WEBVIEW_UNAVAILABLE || failure == BrowserPageError.RENDERER_STOPPED) return
        val previous = webView
        try {
            val view = previous ?: createWebView(container.context, script, adHosts, session)
            if (view.parent !== container) {
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.onResume()
            }
            if (previous == null) view.loadUrl(url)
        } catch (_: RuntimeException) {
            disposeWebView()
            loading = false
            failure = BrowserPageError.WEBVIEW_UNAVAILABLE
        }
    }

    fun unmount(container: FrameLayout) {
        webView?.takeIf { it.parent === container }?.let { view ->
            exitFullscreen()
            view.onPause()
            container.removeView(view)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, script: String, adHosts: Set<String>, session: BrowserSession): WebView {
        val view = WebView(context)
        webView = view
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        val bridge = runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) false else {
                // Only bounded observation data; no download or privileged native commands.
                WebViewCompat.addWebMessageListener(view, "Video2Media", setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                    if (view !== webView) return@addWebMessageListener
                    val origin = httpMediaUrl(sourceOrigin.toString()) ?: return@addWebMessageListener
                    if (isMainFrame && httpMediaUrl(view.url)?.origin() != origin.origin()) return@addWebMessageListener
                    message.data?.let { ingestMessage(it, origin, if (isMainFrame) httpMediaUrl(view.url) else null) }
                }
                true
            }
        }.getOrDefault(false)
        val early = runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) false else {
                WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
                true
            }
        }.getOrDefault(false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) { if (v === webView) progress = newProgress / 100f }
            override fun onShowCustomView(custom: View, callback: CustomViewCallback) {
                if (view !== webView || !view.isAttachedToWindow || fullscreen != null) callback.onCustomViewHidden()
                else fullscreen = WebFullscreen(custom, callback)
            }

            override fun onHideCustomView() { if (view === webView) exitFullscreen() }

            override fun onReceivedTitle(v: WebView?, newTitle: String?) {
                if (v === webView && !newTitle.isNullOrBlank()) title = newTitle
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && httpMediaUrl(request.url.toString()) == null

            override fun onPageStarted(view: WebView, target: String?, favicon: Bitmap?) {
                if (view !== webView || httpMediaUrl(target) == null) return
                startPage(target.orEmpty())
                updateHistory(view)
            }

            override fun onPageFinished(view: WebView, target: String?) {
                if (view !== webView || target != view.url) return
                loading = false
                updateHistory(view)
                if (failure == null && httpMediaUrl(target) != null) {
                    if (!early) view.evaluateJavascript(script, null)
                    else rescan()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, target: String?, isReload: Boolean) {
                if (view !== webView) return
                updateHistory(view)
                if (target != null && httpMediaUrl(target) != null && httpMediaUrl(target) != httpMediaUrl(url)) {
                    updateRoute(target)
                    if (!loading) main.postDelayed({ if (view === webView && url == target) rescan() }, 250)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === webView && request.isForMainFrame) {
                    loading = false
                    failure = BrowserPageError.LOAD_FAILED
                    updateHistory(view)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    disposeWebView(rendererStopped = true)
                    loading = false
                    failure = BrowserPageError.RENDERER_STOPPED
                }
                return true // This WebView cannot be reused. Retry creates a new renderer without losing the UI.
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (view !== webView) return null
                val target = httpMediaUrl(request.url.toString()) ?: return null
                val adHost = generateSequence(target.host) { h -> h.substringAfter('.', "").ifEmpty { null } }.any { it in adHosts }
                if (session.adBlock && adHost && !request.isForMainFrame) return WebResourceResponse("text/plain", "utf-8", byteArrayOf().inputStream())
                if (request.method != "GET") return null
                val epoch = pageEpoch
                val track = target.isPlayerTrackDelivery()
                if (target.isMediaFragment()) {
                    if (track && !adHost && lastTrackSignalEpoch != epoch) {
                        lastTrackSignalEpoch = epoch
                        main.post { if (view === webView && epoch == pageEpoch) notePlayback(PlaybackHint.SEGMENTED) }
                    }
                    return null
                }
                val header = request.requestHeaders
                val accept = header.header("Accept")?.substringBefore(',')?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                val kind = MediaKind.infer(target.toString(), accept)
                // Extensionless assets can be identified by the JS response MIME *after* this callback.
                // Don't fill the bounded map with static page furniture.
                if (!track && kind == MediaKind.OTHER && target.pathSegments.last().substringAfterLast('.', "").lowercase() in STATIC_EXTENSIONS) return null
                val observed = ObservedMediaRequest.from(header)
                if (requestContexts.remember(epoch, target, observed) && (track || (kind != MediaKind.OTHER && kind != MediaKind.TS))) main.post {
                    if (view === webView && epoch == pageEpoch) {
                        if (track && detected.none { it.url == target.toString() }) {
                            if (!adHost) notePlayback(PlaybackHint.SEGMENTED)
                        } else candidate(target, kind, accept, "webview-request", httpMediaUrl(observed.referer), ad = adHost)
                    }
                }
                return null // Do not proxy or execute a second request.
            }
        }
        view.setDownloadListener { target, ua, disposition, mime, length ->
            if (view !== webView) return@setDownloadListener
            val remote = httpMediaUrl(target) ?: return@setDownloadListener
            val filename = disposition?.let { android.webkit.URLUtil.guessFileName(target, it, mime) }
            val kind = MediaKind.infer(target, mime).takeIf { it != MediaKind.OTHER } ?: MediaKind.infer(filename.orEmpty(), mime)
            if (kind == MediaKind.OTHER || kind == MediaKind.TS) return@setDownloadListener
            if (downloadUserAgents.size < 50) ua.safeHeader(512)?.let { downloadUserAgents[remote] = it }
            candidate(remote, kind, mime, "webview-download", httpMediaUrl(view.url), label = filename, size = length.takeIf { it > 0 })
        }
        if (!bridge) main.postDelayed(object : Runnable {
            override fun run() {
                if (view !== webView) return
                val origin = httpMediaUrl(view.url)
                if (view.isAttachedToWindow && origin != null) {
                    val epoch = pageEpoch
                    view.evaluateJavascript("window.__v2Drain ? window.__v2Drain() : []") { result ->
                        if (view === webView && epoch == pageEpoch && result.length <= 600_000) runCatching {
                            val arr = JSONArray(result)
                            if (origin == httpMediaUrl(url)) {
                                for (i in 0 until minOf(80, arr.length())) ingestMessage(arr.optString(i), origin)
                            }
                        }
                    }
                }
                main.postDelayed(this, 1600)
            }
        }, 1600)
        return view
    }

    private fun ingestMessage(payload: String, origin: HttpUrl, livePage: HttpUrl? = null) {
        if (destroyed || payload.length !in 1..24_000) return
        val body = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val frame = httpMediaUrl(body.optString("page"))?.takeIf { it.origin() == origin.origin() } ?: return
        val current = httpMediaUrl(url) ?: return
        if (livePage != null && (frame != livePage || frame != current)) return
        if (body.optString("type") == "playback") {
            val hint = when (body.optString("mode")) {
                "player" -> PlaybackHint.PLAYER
                "in-page" -> PlaybackHint.IN_PAGE
                "segmented" -> PlaybackHint.SEGMENTED
                else -> return
            }
            notePlayback(hint)
            return
        }
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
        if (target.isPlayerTrackDelivery() && (!item.optBoolean("completeTrack") ||
                !(method.startsWith("player-") || method.startsWith("html5")))) return
        val kind = MediaKind.infer(target.toString(), type)
        if (kind == MediaKind.TS) return
        val protection = runCatching { DRMKind.valueOf(item.optString("drm")) }.getOrDefault(DRMKind.NONE)
        ingest(DetectedMedia(
            url = target.toString(), title = item.optString("name").take(512).ifBlank { body.optString("title").take(512).ifBlank { titleFor(target) } },
            kind = kind, mime = type, qualityLabel = item.optString("label").take(40).ifBlank { null },
            drm = protection, pageUrl = current.toString(), extractionMethod = method,
            durationSec = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            width = item.optInt("width").takeIf { it in 1..16384 }, height = item.optInt("height").takeIf { it in 1..16384 },
            requestReferer = frame.toString(), publicPageUrl = YouTubeVideo.parse(frame.toString())?.watchUrl, fromAd = item.optBoolean("ad") || target.isVideoAd()
        ))
    }

    private fun candidate(target: HttpUrl, kind: MediaKind, mime: String?, method: String, ref: HttpUrl?, ad: Boolean = false, label: String? = null, size: Long? = null) {
        if (httpMediaUrl(url) == null) return
        ingest(DetectedMedia(target.toString(), label?.take(250)?.ifBlank { titleFor(target) } ?: titleFor(target), kind,
            mime = mime, pageUrl = url, extractionMethod = method, byteSize = size,
            requestReferer = ref?.toString(), publicPageUrl = YouTubeVideo.parse(ref?.toString())?.watchUrl,
            fromAd = ad || target.isVideoAd()))
    }

    private fun titleFor(url: HttpUrl) = title.takeUnless { it == "تبويب جديد" || it.isBlank() }
        ?: url.pathSegments.last().ifBlank { url.host }

    private fun updateHistory(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    private fun disposeWebView(rendererStopped: Boolean = false) {
        val view = webView
        webView = null
        exitFullscreen()
        main.removeCallbacksAndMessages(null)
        canGoBack = false
        canGoForward = false
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            if (!rendererStopped) view.stopLoading()
            view.destroy()
        }
    }

    fun destroy() {
        destroyed = true
        disposeWebView()
    }

    private fun Map<String, String>.header(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** Explicit URLs are validated; a search term or bare host becomes a safe HTTP(S) target. */
internal fun browserTarget(raw: String): String? {
    val text = raw.trim().takeIf { it.isNotEmpty() && it.length <= 16_384 } ?: return null
    httpMediaUrl(text)?.let { return it.toString() }
    if (text.contains("://")) return null
    if (text.contains('.') && text.none(Char::isWhitespace)) return httpMediaUrl("https://$text")?.toString()
    return httpMediaUrl("https://www.google.com/search?q=${URLEncoder.encode(text, "UTF-8")}")?.toString()
}

internal fun mediaScore(item: DetectedMedia): Int =
    (if (item.canDownload) 100 else 0) + (if (item.kind.isCompleteVideo) 50 else 0) + (if (item.canPlay) 15 else 0) +
        (if (item.probed && item.probeProblem == null) 25 else 0) + (if (item.probeProblem != null) -100 else 0) +
        (if ((item.durationSec ?: 0.0) > 5.0) 18 else 0) + (if (item.extractionMethod.startsWith("html5")) 22 else 0) +
        minOf((item.height ?: 0) / 32, 40) + (if (item.drm.isProtected) -100 else 0) + (if (item.fromAd) -100 else 0)

internal data class BrowserStream(val media: DetectedMedia, val auth: DownloadAuth)

private val STATIC_EXTENSIONS = setOf("css", "js", "mjs", "svg", "png", "jpg", "jpeg", "webp", "gif", "ico", "woff", "woff2", "ttf", "html")
