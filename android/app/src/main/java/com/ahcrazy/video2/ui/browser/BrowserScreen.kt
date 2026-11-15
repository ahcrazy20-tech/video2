package com.ahcrazy.video2.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.DRMKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class BrowserTab(val id: String = UUID.randomUUID().toString()) {
    var title by mutableStateOf("تبويب جديد")
    var url by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var loading by mutableStateOf(false)
    val detected = mutableStateListOf<DetectedMedia>()
    var drm by mutableStateOf(DRMKind.NONE)
    var webView: WebView? = null
}

private fun score(m: DetectedMedia): Int {
    var s = 0
    if (m.kind.isCompleteVideo) s += 50
    if (m.kind.exoSupported) s += 20
    if ((m.durationSec ?: 0.0) > 5) s += 15
    if (m.extractionMethod.contains("html5")) s += 25
    s += minOf((m.height ?: 0) / 10, 40)
    if (m.drm.isProtected) s -= 80
    return s
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(ar: Boolean, openUrl: String?, onConsumeOpenUrl: () -> Unit, onPlayStream: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as android.app.Activity
    val app = activity.app()
    val scope = rememberCoroutineScope()
    val tabs = remember { mutableStateListOf(BrowserTab().also { it.url = "" }) }
    var currentId by remember { mutableStateOf(tabs.first().id) }
    val tab = tabs.firstOrNull { it.id == currentId } ?: tabs.first()
    var address by remember { mutableStateOf("") }
    var showDetector by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showRadar by remember { mutableStateOf(false) }
    var pasteUrl by remember { mutableStateOf("") }
    var pasteTitle by remember { mutableStateOf("") }
    val extractorJs = remember { ctx.assets.open("extractor.js").bufferedReader().readText() }
    val adHosts = remember { ctx.assets.open("adblock_hosts.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
    var adEnabled by remember { mutableStateOf(true) }
    var filterAds by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        app.settings.adBlockEnabled.collect { adEnabled = it }
    }

    LaunchedEffect(openUrl) {
        if (!openUrl.isNullOrBlank()) { loadInto(tab, openUrl); onConsumeOpenUrl() }
    }

    fun ingest(json: String) {
        runCatching {
            val o = JSONObject(json); val item = o.getJSONObject("item")
            val url = item.getString("url")
            val kind = runCatching { MediaKind.valueOf(item.optString("kind", "OTHER")) }.getOrDefault(MediaKind.OTHER)
            val drm = runCatching { DRMKind.valueOf(item.optString("drm", "NONE")) }.getOrDefault(DRMKind.NONE)
            if (filterAds && (url.contains("doubleclick") || url.contains("googlesyndication") || url.contains("preroll"))) return
            val media = DetectedMedia(
                url = url, title = o.optString("title").ifBlank { url.substringAfterLast("/").substringBefore("?") },
                kind = kind, drm = drm, pageUrl = o.optString("page"),
                extractionMethod = item.optString("method", "html5"),
                durationSec = item.optDouble("duration").takeIf { item.has("duration") && it > 0 },
                height = item.optInt("height").takeIf { item.has("height") && it > 0 },
                width = item.optInt("width").takeIf { item.has("width") && it > 0 })
            if (media.isFragment) return
            val idx = tab.detected.indexOfFirst { it.url == url }
            if (idx >= 0) {
                val old = tab.detected[idx]
                tab.detected[idx] = old.copy(drm = if (media.drm.isProtected) media.drm else old.drm,
                    durationSec = old.durationSec ?: media.durationSec, height = old.height ?: media.height)
            } else {
                tab.detected.add(0, media)
                tab.detected.sortByDescending { score(it) }
            }
            if (drm.isProtected) tab.drm = drm
        }
    }

    Column(Modifier.fillMaxSize().background(V2.Bg)) {
        // Toolbar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { tab.webView?.goBack() }) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            IconButton(onClick = { tab.webView?.goForward() }) { Icon(Icons.Filled.ArrowForward, null, tint = Color.White) }
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                placeholder = { Text(if (ar) "ابحث أو اكتب عنوان موقع" else "Search or type a URL", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) loadInto(tab, address) })
            )
            IconButton(onClick = { tab.webView?.reload() }) { Icon(Icons.Filled.Refresh, null, tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BadgedBox(badge = { if (tab.detected.isNotEmpty()) Badge(containerColor = V2.Accent) { Text("${tab.detected.size}", fontSize = 9.sp) } }) {
                IconButton(onClick = { showDetector = true }) { Icon(Icons.Filled.VideoLibrary, null, tint = V2.Gold) }
            }
            IconButton(onClick = { showRadar = true }) { Icon(Icons.Filled.Radar, null, tint = V2.Mint) }
            IconButton(onClick = { showPaste = true }) { Icon(Icons.Filled.ContentPaste, null, tint = V2.Accent) }
            BadgedBox(badge = { Badge(containerColor = V2.CardSoft) { Text("${tabs.size}", fontSize = 9.sp) } }) {
                IconButton(onClick = { showTabs = true }) { Icon(Icons.Filled.Tab, null, tint = Color.White) }
            }
            IconButton(onClick = { val t = BrowserTab(); tabs.add(t); currentId = t.id; address = "" }) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
        if (tab.loading) LinearProgressIndicator(progress = { tab.progress }, Modifier.fillMaxWidth(), color = V2.Accent)
        if (tab.drm.isProtected) {
            Row(Modifier.fillMaxWidth().background(V2.Accent.copy(alpha = .16f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, null, tint = V2.Accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ar) "محتوى محمي بـ DRM" else "DRM-protected content", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(if (ar) "التطبيق لا يكسر الحماية ولن يُحمّل هذا المحتوى. تقدر تشاهده هنا لو الموقع سمح." else "This app never breaks DRM and will not download it.", fontSize = 11.sp, color = V2.TextDim)
                }
                TextButton(onClick = { tab.drm = DRMKind.NONE }) { Text(if (ar) "إخفاء" else "Hide") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = DownloadAuth.CHROME_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(object {
                            @JavascriptInterface fun postMessage(msg: String) {
                                (c as? android.app.Activity)?.runOnUiThread { ingest(msg) }
                            }
                        }, "Video2Bridge")
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView?, p: Int) { tab.progress = p / 100f }
                            override fun onReceivedTitle(v: WebView?, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                tab.loading = true; tab.url = u ?: ""; address = u ?: ""
                                tab.detected.clear(); tab.drm = DRMKind.NONE
                            }
                            override fun onPageFinished(v: WebView?, u: String?) {
                                tab.loading = false; tab.url = u ?: ""
                                v?.evaluateJavascript(extractorJs, null)
                            }
                            override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val host = req?.url?.host ?: return null
                                if (adEnabled && adHosts.any { host == it || host.endsWith(".$it") }) {
                                    return android.webkit.WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                                }
                                return null
                            }
                        }
                        tab.webView = this
                        if (tab.url.isNotBlank()) loadUrl(tab.url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { wv -> if (wv !== tab.webView) { /* tab switched: rebind */ } }
            )
            // Keep one WebView per tab visually: when tab changes we load its URL into the shown WebView
            LaunchedEffect(currentId) {
                val wv = tab.webView
                if (wv != null && wv.url != tab.url && tab.url.isNotBlank()) wv.loadUrl(tab.url)
                address = tab.url
            }
        }
    }

    if (showDetector) {
        ModalBottomSheet(onDismissRequest = { showDetector = false }) {
            DetectorSheet(tab, ar, onClose = { showDetector = false },
                onPlay = { m -> showDetector = false; onPlayStream(m.url, m.title) },
                onDownload = { m, maxH ->
                    scope.launch {
                        val auth = snapshotAuth(tab, m.url)
                        app.downloads.enqueue(m, auth, maxH)
                    }
                })
        }
    }
    if (showRadar) {
        ModalBottomSheet(onDismissRequest = { showRadar = false }) {
            RadarSheet(tab, ar, onPlay = { showRadar = false; onPlayStream(it.url, it.title) },
                onDownload = { m -> scope.launch { app.downloads.enqueue(m, snapshotAuth(tab, m.url), null) }; showRadar = false })
        }
    }
    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Column(Modifier.padding(18.dp)) {
                Text(if (ar) "التبويبات" else "Tabs", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn {
                    items(tabs) { t ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (t.id == currentId) V2.CardSoft else V2.Card)
                            .clickable { currentId = t.id; showTabs = false }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(t.url.ifBlank { "about:blank" }, fontSize = 11.sp, color = V2.TextDim, maxLines = 1)
                            }
                            IconButton(onClick = {
                                tabs.remove(t)
                                if (tabs.isEmpty()) tabs.add(BrowserTab())
                                if (currentId == t.id) currentId = tabs.first().id
                            }) { Icon(Icons.Filled.Close, null, tint = V2.Accent) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Button(onClick = { val t = BrowserTab(); tabs.add(t); currentId = t.id; showTabs = false }, Modifier.fillMaxWidth()) {
                    Text(if (ar) "تبويب جديد" else "New tab")
                }
            }
        }
    }
    if (showPaste) {
        AlertDialog(onDismissRequest = { showPaste = false },
            title = { Text(if (ar) "تحميل من رابط مباشر" else "Download from a direct link") },
            text = {
                Column {
                    OutlinedTextField(pasteUrl, { pasteUrl = it }, label = { Text("URL") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(pasteTitle, { pasteTitle = it }, label = { Text(if (ar) "العنوان (اختياري)" else "Title (optional)") }, singleLine = true)
                    Text(if (ar) "الصق رابط ملف فيديو أو قائمة HLS وهيتحمل على طول للمكتبة." else "Paste a video file or HLS playlist URL.", fontSize = 11.sp, color = V2.TextDim)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pasteUrl.isNotBlank()) { app.downloads.enqueueManual(pasteUrl.trim(), pasteTitle, tab.url); showPaste = false }
                }) { Text(if (ar) "تحميل" else "Download") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}

private fun loadInto(tab: BrowserTab, raw: String) {
    var t = raw.trim(); if (t.isEmpty()) return
    if (!t.contains("://")) {
        t = if (t.contains(".") && !t.contains(" ")) "https://$t"
            else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }
    tab.url = t; tab.detected.clear(); tab.drm = DRMKind.NONE
    tab.webView?.loadUrl(t)
}

private fun snapshotAuth(tab: BrowserTab, mediaUrl: String): DownloadAuth {
    val cookies = CookieManager.getInstance().getCookie(mediaUrl).orEmpty()
    val pageCookies = CookieManager.getInstance().getCookie(tab.url).orEmpty()
    val merged = listOf(cookies, pageCookies).filter { it.isNotBlank() }.joinToString("; ")
    return DownloadAuth(DownloadAuth.CHROME_UA, tab.url.ifBlank { null }, merged.ifBlank { null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectorSheet(tab: BrowserTab, ar: Boolean, onClose: () -> Unit,
                          onPlay: (DetectedMedia) -> Unit, onDownload: (DetectedMedia, Int?) -> Unit) {
    var onlyPlayable by remember { mutableStateOf(false) }
    var qualityFor by remember { mutableStateOf<DetectedMedia?>(null) }
    val items = tab.detected.filter { !onlyPlayable || it.kind.exoSupported }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ar) "الوسائط المكتشفة" else "Detected media", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { tab.detected.clear() }) { Text(if (ar) "مسح الكل" else "Clear") }
            TextButton(onClick = onClose) { Text(if (ar) "إغلاق" else "Close") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ar) "القابل للتشغيل بس" else "Only playable", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = onlyPlayable, onCheckedChange = { onlyPlayable = it })
        }
        Text("${items.size} " + if (ar) "مصدر" else "sources", fontSize = 12.sp, color = V2.TextDim)
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) EmptyState(Icons.Filled.VideoLibrary,
            if (ar) "لسه مفيش وسائط" else "No media yet",
            if (ar) "شغّل الفيديو في الصفحة وهيظهر هنا على طول" else "Play the video on the page and it will appear here")
        LazyColumn {
            items(items) { m -> MediaCard(m, ar, onPlay = { onPlay(m) }, onDownload = { onDownload(m, null) }, onQuality = { qualityFor = m }) }
        }
    }
    qualityFor?.let { m ->
        AlertDialog(onDismissRequest = { qualityFor = null },
            title = { Text(if (ar) "اختيار الجودة" else "Pick quality") },
            text = {
                Column {
                    (listOf<Int?>(null) + listOf(1080, 720, 480, 360)).forEach { h ->
                        TextButton(onClick = { onDownload(m, h); qualityFor = null }) {
                            Text(if (h == null) (if (ar) "تلقائي (الأعلى)" else "Auto (highest)") else "${h}p")
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { qualityFor = null }) { Text("✕") } })
    }
}

@Composable
private fun RadarSheet(tab: BrowserTab, ar: Boolean, onPlay: (DetectedMedia) -> Unit, onDownload: (DetectedMedia) -> Unit) {
    val groups = tab.detected.groupBy { runCatching { java.net.URL(it.url).host }.getOrDefault("—") }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text(if (ar) "الرادار الذكي للوسائط" else "Smart Media Radar", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(if (ar) "بيجمع كل اللي اترصد في الصفحة دي حسب المصدر، وبأعلى جودة الأول." else "Everything sniffed on this page, grouped by source, best quality first.", fontSize = 12.sp, color = V2.TextDim)
        Spacer(Modifier.height(10.dp))
        if (groups.isEmpty()) EmptyState(Icons.Filled.Radar, if (ar) "الرادار فاضي" else "Radar is empty",
            if (ar) "افتح صفحة فيديو وشغّله وهنا هيتجمع كل حاجة" else "Open a video page and press play")
        LazyColumn {
            groups.forEach { (host, list) ->
                item { Text("🌐 $host · ${list.size}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                items(list) { m -> MediaCard(m, ar, onPlay = { onPlay(m) }, onDownload = { onDownload(m) }, onQuality = { onDownload(m) }) }
            }
        }
    }
}

@Composable
fun MediaCard(m: DetectedMedia, ar: Boolean, onPlay: () -> Unit, onDownload: () -> Unit, onQuality: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column {
            Text(m.title.ifBlank { m.url }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(m.kind.titleAr, V2.Gold)
                (m.resolutionText(ar))?.let { Chip(it, V2.Mint) }
                if (m.durationSec != null) Chip(m.durationText, V2.TextDim)
                Chip(m.extractionMethod, V2.TextDim)
                if (m.drm.isProtected) Chip("DRM", V2.Accent)
            }
            Text(m.url, fontSize = 10.sp, color = V2.TextDim, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, enabled = m.kind.exoSupported) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Text(if (ar) "تشغيل" else "Play", fontSize = 12.sp)
                }
                Button(onClick = onDownload, enabled = m.canDownload) {
                    Icon(Icons.Filled.Download, null, Modifier.size(16.dp)); Text(if (ar) "تحميل" else "Download", fontSize = 12.sp)
                }
                if (m.kind == MediaKind.HLS) TextButton(onClick = onQuality) { Text(if (ar) "بالجودة…" else "Quality…", fontSize = 12.sp) }
            }
            if (m.drm.isProtected) Text(if (ar) "محمي بـ DRM — مش هيتحمل." else "DRM protected — won't download.", fontSize = 11.sp, color = V2.Accent)
            if (m.url.startsWith("blob:")) Text("blob: — شغّله في المتصفح بس", fontSize = 11.sp, color = V2.Gold)
        }
    }
}

@Composable fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .15f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

private fun DetectedMedia.resolutionText(ar: Boolean): String? =
    qualityLabel ?: height?.let { "${it}p" }
