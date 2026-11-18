package com.ahcrazy.video2.ui.browser

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserScreen(ar: Boolean, session: BrowserSession, openUrl: String?, onConsumeOpenUrl: () -> Unit, onPlayStream: (BrowserStream) -> Unit) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val tab = session.current
    val snackbars = remember { SnackbarHostState() }
    val filterAds by app.settings.adBlockFilterVideoAds.collectAsState(initial = true)
    val adBlock by app.settings.adBlockEnabled.collectAsState(initial = true)
    val preferredHeight by app.settings.downloadMaxHeight.collectAsState(initial = 0)
    var shown by remember(tab.id) { mutableStateOf("") }
    var qualityFor by remember(tab.id) { mutableStateOf<String?>(null) }
    var checkingQuality by remember(tab.id) { mutableStateOf(false) }
    var refreshing by remember(tab.id) { mutableStateOf(false) }
    var pasteUrl by remember { mutableStateOf("") }
    var pasteTitle by remember { mutableStateOf("") }
    val extractor = remember { ctx.assets.open("extractor.js").bufferedReader().use { it.readText() } }
    val adHosts = remember { ctx.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() } }
    val detected = tab.detected.filter { !filterAds || !it.fromAd }.sortedByDescending(::mediaScore)

    SideEffect { session.adBlock = adBlock }
    BackHandler(enabled = tab.canGoBack) { tab.goBack() }
    LaunchedEffect(openUrl) {
        if (!openUrl.isNullOrBlank()) { session.current.open(openUrl); onConsumeOpenUrl() }
    }
    LaunchedEffect(tab.id, qualityFor) {
        val item = tab.detected.firstOrNull { it.url == qualityFor } ?: return@LaunchedEffect
        val page = tab.url
        checkingQuality = true
        try {
            val result = if (item.variants.isEmpty() && !item.probed) app.downloads.probe(item, tab.auth(item)) else item
            if (tab.url == page && tab.detected.any { it.url == item.url }) tab.ingest(result)
        } finally { checkingQuality = false }
    }

    fun download(item: DetectedMedia, variant: HlsVariant? = null, auto: Boolean = false) {
        val current = tab.detected.firstOrNull { it.url == item.url } ?: return
        if (!current.canDownload) return
        val maxHeight = variant?.height ?: preferredHeight.takeIf { it > 0 && !auto }
        val job = app.downloads.enqueue(current, tab.auth(current), maxHeight, variant?.url)
        shown = ""
        qualityFor = null
        if (job.state.isBusy) scope.launch {
            snackbars.showSnackbar(if (ar) "اتضاف لقائمة التحميلات" else "Added to Downloads")
        }
    }

    fun play(item: DetectedMedia) {
        val current = tab.detected.firstOrNull { it.url == item.url } ?: return
        if (current.drm.isProtected || !current.kind.exoSupported) return
        shown = ""
        onPlayStream(BrowserStream(current, tab.auth(current)))
    }

    fun submitAddress() {
        if (tab.address.isBlank()) return
        focus.clearFocus()
        if (!tab.open(tab.address)) scope.launch {
            snackbars.showSnackbar(if (ar) "اكتب رابط HTTP أو HTTPS صالح، أو كلمة للبحث." else "Enter a valid HTTP or HTTPS URL, or a search term.")
        }
    }

    Box(Modifier.fillMaxSize().background(V2.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = tab::goBack, enabled = tab.canGoBack) {
                    Icon(Icons.Filled.ArrowBack, if (ar) "رجوع" else "Back", tint = if (tab.canGoBack) Color.White else V2.TextDim)
                }
                IconButton(onClick = tab::goForward, enabled = tab.canGoForward) {
                    Icon(Icons.Filled.ArrowForward, if (ar) "للأمام" else "Forward", tint = if (tab.canGoForward) Color.White else V2.TextDim)
                }
                OutlinedTextField(tab.address, { tab.address = it },
                    placeholder = { Text(if (ar) "ابحث أو اكتب رابط" else "Search or type a URL", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.weight(1f).testTag("browser.address"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                    trailingIcon = {
                        IconButton(onClick = { submitAddress() }, enabled = tab.address.isNotBlank(), modifier = Modifier.testTag("browser.go")) {
                            Icon(Icons.Filled.Search, if (ar) "فتح الرابط أو البحث" else "Open URL or search")
                        }
                    })
                IconButton(onClick = tab::reload, enabled = tab.url.isNotBlank()) {
                    Icon(Icons.Filled.Refresh, if (ar) "تحديث" else "Reload", tint = if (tab.url.isNotBlank()) Color.White else V2.TextDim)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BadgedBox(badge = { if (detected.isNotEmpty()) Badge(containerColor = V2.Accent) { Text("${detected.size}") } }) {
                    IconButton(onClick = { shown = "detector"; tab.rescan() }) {
                        Icon(Icons.Filled.VideoLibrary, if (ar) "الوسائط المكتشفة" else "Detected media", tint = V2.Gold)
                    }
                }
                IconButton(onClick = { shown = "radar"; tab.rescan() }) { Icon(Icons.Filled.Radar, if (ar) "الرادار" else "Radar", tint = V2.Mint) }
                IconButton(onClick = { shown = "paste" }) { Icon(Icons.Filled.ContentPaste, if (ar) "تحميل رابط مباشر" else "Direct download", tint = V2.Accent) }
                BadgedBox(badge = { Badge(containerColor = V2.CardSoft) { Text("${session.tabs.size}") } }) {
                    IconButton(onClick = { shown = "tabs" }) { Icon(Icons.Filled.Tab, if (ar) "التبويبات" else "Tabs", tint = Color.White) }
                }
                IconButton(onClick = { session.add() }) { Icon(Icons.Filled.Add, if (ar) "تبويب جديد" else "New tab", tint = Color.White) }
            }
            if (tab.loading) LinearProgressIndicator(progress = { tab.progress }, Modifier.fillMaxWidth(), color = V2.Accent)
            if (tab.drm.isProtected && !tab.hideDrmBanner) {
                Row(Modifier.fillMaxWidth().background(V2.Accent.copy(alpha = 0.16f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = V2.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ar) "الصفحة فيها مصدر محمي بـ DRM. المصدر المحمي مش هيتحمّل." else "A protected source was detected. DRM sources cannot be downloaded.",
                        modifier = Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { tab.hideDrmBanner = true }) { Text(if (ar) "إخفاء" else "Hide") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (tab.url.isBlank()) {
                    BrowserStart(ar, onSearch = { focus.clearFocus(); tab.open("https://www.google.com/") }, onDirect = { shown = "paste" })
                } else {
                    // The native view is clipped to the page; it never covers Compose's toolbars or tab buttons.
                    key(tab.id) {
                        AndroidView(
                            factory = { c -> FrameLayout(c).apply { clipChildren = true; clipToPadding = true } },
                            modifier = Modifier.fillMaxSize().testTag("browser.viewport"),
                            onReset = null,
                            onRelease = tab::unmount,
                            update = { tab.mount(it, extractor, adHosts, session) }
                        )
                    }
                    tab.failure?.let { BrowserFailure(ar, it, onRetry = tab::reload, onHome = tab::goHome) }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }

    if (shown == "detector" || shown == "radar") {
        val radar = shown == "radar"
        ModalBottomSheet(onDismissRequest = { shown = ""; qualityFor = null }) {
            Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val heading = if (radar) (if (ar) "الرادار الذكي" else "Smart Media Radar")
                        else (if (ar) "الوسائط المكتشفة" else "Detected media")
                    Text(heading, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(enabled = !refreshing, onClick = {
                        tab.rescan()
                        if (radar) scope.launch {
                            val page = tab.url
                            refreshing = true
                            try {
                                for (item in detected.filterNot { it.drm.isProtected || it.probed }.take(8)) {
                                    val result = app.downloads.probe(item, tab.auth(item))
                                    if (tab.url != page) break
                                    if (tab.detected.any { it.url == item.url }) tab.ingest(result)
                                }
                            } finally { refreshing = false }
                        }
                    }) { Text(if (ar) "فحص" else "Scan") }
                    TextButton(onClick = { shown = "" }) { Text(if (ar) "إغلاق" else "Close") }
                }
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (detected.isEmpty()) EmptyState(if (radar) Icons.Filled.Radar else Icons.Filled.VideoLibrary,
                    if (ar) "لسه مفيش وسائط" else "No media yet",
                    if (ar) "شغّل الفيديو جوه الصفحة وبعدها افتح قائمة الالتقاط. بعض المشغّلات ما بتكشفش الرابط قبل التشغيل." else "Play the video on the page first. Some players only expose the stream after playback starts.")
                LazyColumn {
                    val groups = if (radar) detected.groupBy { httpMediaUrl(it.url)?.host.orEmpty() } else mapOf("" to detected)
                    groups.forEach { (host, items) ->
                        if (host.isNotEmpty()) item { Text("$host · ${items.size}", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
                        items(items, key = { it.id }) { m ->
                            MediaCard(m, ar, onPlay = { play(m) }, onDownload = { download(m) }, onQuality = { qualityFor = m.url })
                        }
                    }
                }
            }
        }
    }

    val quality = tab.detected.firstOrNull { it.url == qualityFor }
    if (quality != null) {
        AlertDialog(onDismissRequest = { qualityFor = null }, title = { Text(if (ar) "جودات البث المتاحة" else "Available stream qualities") }, text = {
            if (checkingQuality) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (quality.drm.isProtected) Text(if (ar) "البث محمي بـ DRM." else "This stream is DRM-protected.")
            else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                item { TextButton(onClick = { download(quality, auto = true) }) { Text(if (ar) "تلقائي (أعلى جودة)" else "Auto (highest)") } }
                if (quality.variants.isEmpty()) item {
                    Text(if (ar) "مفيش جودات منفصلة ظهرت في قائمة البث." else "No separate renditions found in this playlist.", color = V2.TextDim)
                }
                items(quality.variants, key = { it.id }) { variant ->
                    TextButton(onClick = { download(quality, variant) }) { Text(variant.qualityLabel) }
                }
            }
        }, confirmButton = { TextButton(onClick = { qualityFor = null }) { Text(if (ar) "إغلاق" else "Close") } })
    }

    if (shown == "tabs") {
        ModalBottomSheet(onDismissRequest = { shown = "" }) {
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 22.dp)) {
                Text(if (ar) "التبويبات" else "Tabs", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                LazyColumn(Modifier.heightIn(max = 430.dp)) {
                    items(session.tabs, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (item.id == session.currentId) V2.CardSoft else V2.Card)
                            .clickable { session.currentId = item.id; shown = "" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontSize = 13.sp, maxLines = 1)
                                Text(item.url, fontSize = 11.sp, color = V2.TextDim, maxLines = 1)
                            }
                            IconButton(onClick = { session.close(item) }) { Icon(Icons.Filled.Close, if (ar) "إغلاق التبويب" else "Close tab", tint = V2.Accent) }
                        }
                    }
                }
                Button(onClick = { session.add(); shown = "" }, Modifier.fillMaxWidth()) { Text(if (ar) "تبويب جديد" else "New tab") }
            }
        }
    }
    if (shown == "paste") {
        val target = httpMediaUrl(pasteUrl.trim())
        AlertDialog(onDismissRequest = { shown = "" }, title = { Text(if (ar) "تحميل من رابط مباشر" else "Download from a direct link") },
            text = {
                Column {
                    OutlinedTextField(pasteUrl, { pasteUrl = it }, label = { Text("HTTP / HTTPS") }, singleLine = true)
                    OutlinedTextField(pasteTitle, { pasteTitle = it }, label = { Text(if (ar) "العنوان (اختياري)" else "Title (optional)") }, singleLine = true)
                    Text(if (ar) "الصق رابط ملف فيديو أو بث HLS، مش عنوان صفحة المشاهدة." else "Paste a media file or HLS URL, not the watch page.", fontSize = 12.sp, color = V2.TextDim)
                }
            },
            confirmButton = { TextButton(enabled = target != null, onClick = {
                if (target != null) {
                    val captured = tab.detected.firstOrNull { it.url == target.toString() }
                    if (captured?.drm?.isProtected == true) app.downloads.enqueue(captured, tab.auth(captured))
                    else app.downloads.enqueueManual(target.toString(), pasteTitle, tab.url.ifBlank { null }, tab.auth(captured), preferredHeight.takeIf { it > 0 })
                    pasteUrl = ""; pasteTitle = ""; shown = ""
                }
            }) { Text(if (ar) "تحميل" else "Download") } },
            dismissButton = { TextButton(onClick = { shown = "" }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}

@Composable
private fun BrowserStart(ar: Boolean, onSearch: () -> Unit, onDirect: () -> Unit) {
    BrowserPanel(Modifier.testTag("browser.start")) {
        Icon(Icons.Filled.Explore, null, Modifier.size(46.dp), tint = V2.Mint)
        Text(if (ar) "متصفح فيديو ٢" else "Video2 Browser", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (ar) "اكتب رابط أو كلمة للبحث في الشريط فوق. شغّل الفيديو على الصفحة، وبعدها افتح «الوسائط المكتشفة»."
            else "Enter a URL or search above. Play the video on the page, then open Detected media.",
            color = V2.TextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
        Button(onClick = onSearch) { Text(if (ar) "افتح Google" else "Open Google") }
        TextButton(onClick = onDirect, modifier = Modifier.testTag("browser.start.direct")) {
            Text(if (ar) "عندي رابط تحميل مباشر" else "I have a direct download URL")
        }
    }
}

@Composable
private fun BrowserFailure(ar: Boolean, failure: BrowserPageError, onRetry: () -> Unit, onHome: () -> Unit) {
    val (title, details) = when (failure) {
        BrowserPageError.LOAD_FAILED -> if (ar) "الصفحة ما اتفتحتش" to "اتأكد من الاتصال بالإنترنت أو جرّب رابط تاني."
            else "Couldn't open the page" to "Check your connection or try a different URL."
        BrowserPageError.WEBVIEW_UNAVAILABLE -> if (ar) "محرك المتصفح غير متاح" to "حدّث Android System WebView أو Chrome، وجرب تاني."
            else "Browser engine unavailable" to "Update Android System WebView or Chrome, then try again."
        BrowserPageError.RENDERER_STOPPED -> if (ar) "عرض الصفحة اتوقف" to "جرب تفتح الصفحة من جديد."
            else "Page rendering stopped" to "Try opening the page again."
    }
    BrowserPanel(Modifier.testTag("browser.error")) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(42.dp), tint = V2.Gold)
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(details, color = V2.TextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
        Button(onClick = onRetry) { Text(if (ar) "حاول تاني" else "Retry") }
        TextButton(onClick = onHome) { Text(if (ar) "صفحة البداية" else "Start page") }
    }
}

@Composable
private fun BrowserPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(V2.Bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 390.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content)
    }
}

@Composable
internal fun MediaCard(m: DetectedMedia, ar: Boolean, onPlay: () -> Unit, onDownload: () -> Unit, onQuality: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column {
            Text(m.title.ifBlank { httpMediaUrl(m.url)?.host.orEmpty() }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (ar) m.kind.titleAr else m.kind.titleEn, V2.Gold)
                (m.qualityLabel ?: m.height?.let { "${it}p" })?.let { Chip(it, V2.Mint) }
                if (m.durationSec != null) Chip(m.durationText, V2.TextDim)
                if (m.byteSize != null) Chip(L10n.fmtBytes(m.byteSize), V2.TextDim)
                if (m.drm.isProtected) Chip("DRM", V2.Accent)
            }
            Text("${httpMediaUrl(m.url)?.host.orEmpty()} · ${m.extractionMethod}", fontSize = 11.sp, color = V2.TextDim,
                maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPlay, enabled = m.kind.exoSupported && !m.drm.isProtected, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Text(if (ar) "تشغيل" else "Play", fontSize = 12.sp)
                }
                Button(onClick = onDownload, enabled = m.canDownload, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Filled.Download, null, Modifier.size(16.dp)); Text(if (ar) "تحميل" else "Download", fontSize = 12.sp)
                }
                if (m.kind == MediaKind.HLS) TextButton(onClick = onQuality, enabled = m.canDownload, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (ar) "الجودة" else "Quality", fontSize = 12.sp)
                }
            }
            if (m.drm.isProtected) Text(if (ar) "محمي بـ DRM — التشغيل في صفحة الموقع لو متاح." else "DRM protected; use the original page for playback.", fontSize = 11.sp, color = V2.Accent)
            else if (m.kind == MediaKind.DASH) Text(if (ar) "DASH للتشغيل فقط" else "DASH is playback-only", fontSize = 11.sp, color = V2.TextDim)
        }
    }
}

@Composable fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
    }
}
