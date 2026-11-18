package com.ahcrazy.video2.ui.browser

import android.view.ViewGroup
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isYouTubePage
import com.ahcrazy.video2.core.media.manifestKind
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserScreen(
    ar: Boolean, session: BrowserSession, openUrl: String?, onConsumeOpenUrl: () -> Unit,
    onPlayStream: (BrowserStream) -> Unit, onOpenDownloads: () -> Unit = {}
) {
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
    val captureRevision = tab.captureRevision
    val fullscreen = tab.fullscreen
    var qualityFor by remember(tab.id, captureRevision) { mutableStateOf<String?>(null) }
    var checkingUrls by remember(tab.id, captureRevision) { mutableStateOf(emptyList<String>()) }
    var pasteUrl by remember { mutableStateOf("") }
    var pasteTitle by remember { mutableStateOf("") }
    val extractor = remember { ctx.assets.open("extractor.js").bufferedReader().use { it.readText() } }
    val adHosts = remember { ctx.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() } }
    val detected = tab.detected.filter { !filterAds || !it.fromAd }.sortedByDescending(::mediaScore)

    SideEffect { session.adBlock = adBlock }
    BackHandler(enabled = fullscreen != null || tab.canGoBack) { if (fullscreen != null) tab.exitFullscreen() else tab.goBack() }
    LaunchedEffect(openUrl) {
        if (!openUrl.isNullOrBlank()) { session.current.open(openUrl); onConsumeOpenUrl() }
    }
    LaunchedEffect(tab.id, captureRevision, shown, checkingUrls) {
        if (shown != "capture" || checkingUrls.isEmpty()) return@LaunchedEffect
        val slots = Semaphore(3)
        tab.detected.filter { !it.drm.isProtected && it.url in checkingUrls }.map { candidate ->
            async {
                slots.withPermit {
                    val result = app.downloads.probe(candidate, tab.auth(candidate))
                    if (tab.captureRevision == captureRevision && tab.detected.any { it.url == candidate.url }) tab.ingest(result)
                }
            }
        }.awaitAll()
        checkingUrls = emptyList()
    }

    fun verify(url: String?) {
        if (checkingUrls.isNotEmpty()) return
        checkingUrls = if (url != null) listOf(url) else detected.filterNot { it.drm.isProtected }
            .sortedBy { it.probed && it.probeProblem == null }.take(6).map { it.url }
    }

    fun queued() {
        scope.launch {
            if (snackbars.showSnackbar(if (ar) "اتضاف لقائمة التحميلات" else "Added to Downloads",
                actionLabel = if (ar) "التحميلات" else "Downloads", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    fun download(item: DetectedMedia, variant: HlsVariant? = null, auto: Boolean = false) {
        val current = tab.detected.firstOrNull { it.url == item.url } ?: return
        if (!current.canDownload) return
        val maxHeight = variant?.height ?: preferredHeight.takeIf { it > 0 && !auto }
        val job = app.downloads.enqueue(current, tab.auth(current), maxHeight, variant?.url)
        shown = ""
        qualityFor = null
        checkingUrls = emptyList()
        if (job.state.isBusy) queued()
    }

    fun play(item: DetectedMedia) {
        val current = tab.detected.firstOrNull { it.url == item.url } ?: return
        if (!current.canPlay) return
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
            if (fullscreen == null) Surface(color = V2.Bg) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = tab::goBack, enabled = tab.canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, if (ar) "رجوع" else "Back", tint = if (tab.canGoBack) Color.White else V2.TextDim)
                        }
                        IconButton(onClick = tab::goForward, enabled = tab.canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, if (ar) "للأمام" else "Forward", tint = if (tab.canGoForward) Color.White else V2.TextDim)
                        }
                        OutlinedTextField(tab.address, { tab.address = it },
                            placeholder = { Text(if (ar) "بحث أو رابط" else "Search or URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true, modifier = Modifier.weight(1f).testTag("browser.address"),
                            shape = RoundedCornerShape(21.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = V2.CardSoft, unfocusedContainerColor = V2.Card,
                                unfocusedBorderColor = V2.Outline),
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
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 5.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { focus.clearFocus(); shown = "capture"; tab.rescan() },
                            modifier = Modifier.weight(1f).testTag("browser.capture"), contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = V2.Mint.copy(alpha = .14f), contentColor = V2.Mint)) {
                            Icon(Icons.Filled.Radar, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (ar) "الالتقاط" else "Capture", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (detected.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Badge(containerColor = V2.Mint, contentColor = V2.Bg) { Text("${detected.size}") }
                            }
                        }
                        IconButton(onClick = { shown = "paste" }) { Icon(Icons.Filled.ContentPaste, if (ar) "تحميل رابط مباشر" else "Direct download", tint = V2.Sky) }
                        BadgedBox(badge = { if (session.tabs.size > 1) Badge(containerColor = V2.CardSoft) { Text("${session.tabs.size}") } }) {
                            IconButton(onClick = { shown = "tabs" }) { Icon(Icons.Filled.Tab, if (ar) "التبويبات" else "Tabs") }
                        }
                        IconButton(onClick = { session.add() }) { Icon(Icons.Filled.Add, if (ar) "تبويب جديد" else "New tab") }
                    }
                    if (tab.loading) LinearProgressIndicator(progress = { tab.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = V2.Mint)
                    else HorizontalDivider(color = V2.Outline)
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
                    if (fullscreen != null) {
                        key(fullscreen.view) {
                            AndroidView(
                                factory = { c -> FrameLayout(c).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                                modifier = Modifier.fillMaxSize(), onReset = null,
                                onRelease = { it.removeAllViews() },
                                update = { box ->
                                    val custom = fullscreen.view
                                    if (custom.parent !== box) {
                                        (custom.parent as? ViewGroup)?.removeView(custom)
                                        box.addView(custom, FrameLayout.LayoutParams(-1, -1))
                                    }
                                }
                            )
                        }
                        Row(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = .70f))) {
                            IconButton(onClick = { shown = "capture"; tab.rescan() }) { Icon(Icons.Filled.Radar, if (ar) "الالتقاط" else "Capture", tint = V2.Mint) }
                            IconButton(onClick = tab::exitFullscreen) { Icon(Icons.Filled.FullscreenExit, if (ar) "خروج من ملء العرض" else "Exit full screen", tint = Color.White) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }

    if (shown == "capture") {
        val quality = detected.firstOrNull { it.url == qualityFor }
        CaptureSheet(ar, tab.url, detected, captureMessage(ar, tab.url, tab.playbackHint, tab.drm.isProtected, tab.detected.size - detected.size),
            checkingUrls.isNotEmpty(), quality, preferredHeight,
            onClose = { shown = ""; qualityFor = null; checkingUrls = emptyList() },
            onScan = tab::rescan, onVerify = ::verify, onPlay = ::play, onDownload = ::download,
            onQuality = {
                qualityFor = it?.url
                if (it != null && (!it.probed || it.probeProblem != null)) verify(it.url)
            },
            onYoutubeHelp = {
                shown = ""; checkingUrls = emptyList()
                session.add(); session.current.open("https://support.google.com/youtube/answer/56100")
            })
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
                    OutlinedTextField(pasteUrl, { pasteUrl = it }, label = { Text("HTTP / HTTPS") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                    OutlinedTextField(pasteTitle, { pasteTitle = it }, label = { Text(if (ar) "العنوان (اختياري)" else "Title (optional)") }, singleLine = true)
                    Text(if (ar) "الصق رابط ملف فيديو أو بث HLS، مش عنوان صفحة المشاهدة." else "Paste a media file or HLS URL, not the watch page.", color = V2.TextDim)
                }
            },
            confirmButton = { TextButton(enabled = target != null, onClick = {
                if (target != null) {
                    val captured = tab.detected.firstOrNull { it.url == target.toString() }
                    val page = target.isYouTubePage() && target.manifestKind() == null && captured == null
                    if (page) {
                        shown = ""
                        tab.open(target.toString())
                    } else {
                        val maxHeight = preferredHeight.takeIf { it > 0 }
                        val job = if (captured != null) app.downloads.enqueue(captured.copy(title = pasteTitle.ifBlank { captured.title }), tab.auth(captured), maxHeight)
                            else app.downloads.enqueueManual(target.toString(), pasteTitle, tab.url.ifBlank { null }, tab.auth(), maxHeight)
                        pasteUrl = ""; pasteTitle = ""; shown = ""
                        if (job.state.isBusy) queued() else onOpenDownloads()
                    }
                }
            }) { Text(if (target?.isYouTubePage() == true && target.manifestKind() == null && detected.none { it.url == target.toString() })
                (if (ar) "افتح صفحة المشاهدة" else "Open watch page") else (if (ar) "تحميل" else "Download")) } },
            dismissButton = { TextButton(onClick = { shown = "" }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}

@Composable
private fun BrowserStart(ar: Boolean, onSearch: () -> Unit, onDirect: () -> Unit) {
    BrowserPanel(Modifier.testTag("browser.start")) {
        Surface(shape = RoundedCornerShape(25.dp), color = V2.CardSoft) {
            Icon(Icons.Filled.Explore, null, Modifier.padding(18.dp).size(42.dp), tint = V2.Mint)
        }
        Text(if (ar) "متصفح فيديو ٢" else "Video2 Browser", style = MaterialTheme.typography.headlineMedium)
        Text(if (ar) "افتح صفحة وشغّل الفيديو، وبعدها اضغط «الالتقاط» عشان تشوف المصادر المتاحة."
            else "Open a page and play the video, then tap Capture to see available sources.",
            color = V2.TextDim, textAlign = TextAlign.Center)
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

@Composable fun Chip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f), contentColor = color) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}
