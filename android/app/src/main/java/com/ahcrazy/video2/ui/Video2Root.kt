package com.ahcrazy.video2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.DownloadJob
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.ui.browser.BrowserScreen
import com.ahcrazy.video2.ui.browser.BrowserSession
import com.ahcrazy.video2.ui.browser.BrowserSessionStore
import com.ahcrazy.video2.ui.browser.BrowserStream
import com.ahcrazy.video2.ui.downloads.DownloadsScreen
import com.ahcrazy.video2.ui.library.LibraryScreen
import com.ahcrazy.video2.ui.magic.MagicSearchScreen
import com.ahcrazy.video2.ui.magic.MagicSearchState
import com.ahcrazy.video2.ui.player.PlayerScreen
import com.ahcrazy.video2.ui.settings.SettingsScreen
import com.ahcrazy.video2.ui.theme.V2
import com.ahcrazy.video2.ui.theme.Video2Theme
import com.ahcrazy.video2.ui.theme.Video2Appearance
import com.ahcrazy.video2.ui.theme.galaxyBackground
import com.ahcrazy.video2.ui.translate.ConversionScreen
import com.ahcrazy.video2.ui.dubbing.DubbingSheet
import com.ahcrazy.video2.ui.translate.SubtitleReviewScreen
import com.ahcrazy.video2.ui.translate.TranslateScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Video2Root() {
    val activity = LocalContext.current as android.app.Activity
    val app = activity.app()
    val lang by app.settings.lang.collectAsState(initial = "ar")
    val ar = lang != "en"
    val appearance by app.settings.appearance.collectAsState(initial = "galaxy")
    var tab by rememberSaveable { mutableStateOf(AppTab.BROWSER) }
    val tabState = rememberSaveableStateHolder()
    var locked by remember { mutableStateOf(app.settings.hasAppPassword()) }
    var playingVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var playingStream by remember { mutableStateOf<BrowserStream?>(null) }
    val browserStore = remember(app) { BrowserSessionStore(app) }
    // Own live WebViews above all app tabs. If Android recreates the Activity, restore addresses
    // before mounting the browser; the new WebView loads the page only when the tab is visible.
    val browser by produceState<BrowserSession?>(null, browserStore) {
        val pages = withContext(Dispatchers.IO) { browserStore.load() }
        value = BrowserSession(pages)
    }
    val session = browser
    val magicSearch = remember { MagicSearchState() }
    DisposableEffect(session) { onDispose { session?.destroy() } }
    LaunchedEffect(session) {
        if (session != null) snapshotFlow { session.pages() }.collect(browserStore::save)
    }
    fun selectTab(next: AppTab) {
        if (tab == AppTab.BROWSER && next != tab) session?.pages()?.let(browserStore::save)
        tab = next
    }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var browserRecovery by remember { mutableStateOf<DownloadJob?>(null) }
    var translatePreselect by remember { mutableStateOf<SavedVideo?>(null) }
    var reviewJob by remember { mutableStateOf<TranslationJob?>(null) }
    var dubTarget by remember { mutableStateOf<SavedVideo?>(null) }
    var apiKeyRequest by remember { mutableStateOf<String?>(null) }
    var showConvert by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Video2Theme(rtl = ar, appearance = Video2Appearance.fromStored(appearance)) {
        Box(Modifier.fillMaxSize().galaxyBackground()) {
            when {
                locked -> LockScreen(ar, onUnlock = { locked = false })
                playingVideo != null || playingStream != null -> PlayerScreen(
                    video = playingVideo, streamUrl = playingStream?.media?.url, streamTitle = playingStream?.media?.title, ar = ar,
                    streamKind = playingStream?.media?.kind, streamAuth = playingStream?.auth,
                    onBack = { playingVideo = null; playingStream = null })
                reviewJob != null -> SubtitleReviewScreen(reviewJob!!, ar) { reviewJob = null }
                showConvert -> ConversionScreen(ar) { showConvert = false }
                else -> AppTabs(tab, lang, tabState, onSelect = ::selectTab,
                    immersive = tab == AppTab.BROWSER && session?.current?.fullscreen != null) { active ->
                    when (active) {
                        AppTab.BROWSER -> if (session == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (ar) "بنرجّع صفحة المتصفح…" else "Restoring the browser…", color = V2.TextDim)
                            }
                        } else BrowserScreen(ar, session, browserUrl, { browserUrl = null }, onPlayStream = { playingStream = it },
                            onOpenDownloads = { selectTab(AppTab.DOWNLOADS) }, recoverFrom = browserRecovery, onConsumeRecovery = { browserRecovery = null })
                        AppTab.LIBRARY -> LibraryScreen(ar,
                            onPlay = { playingVideo = it },
                            onTranslate = { translatePreselect = it; tab = AppTab.TRANSLATE },
                            onReview = { v ->
                                app.translations.jobs.value.firstOrNull { it.videoId == v.id }?.let { reviewJob = it }
                            },
                            onDub = { dubTarget = it },
                            onConvert = { showConvert = true })
                        AppTab.TRANSLATE -> TranslateScreen(ar, translatePreselect, { translatePreselect = null }, onReviewJob = { reviewJob = it })
                        AppTab.DOWNLOADS -> DownloadsScreen(ar, onOpenSource = { job -> browserRecovery = job; tab = AppTab.BROWSER },
                            onOpenLibrary = { tab = AppTab.LIBRARY })
                        AppTab.SETTINGS -> SettingsScreen(ar, onLangChange = { code -> scope.launch { app.settings.setLang(code) } },
                            onLockNow = { locked = true }, apiKeyRequest = apiKeyRequest, onApiKeyRequestHandled = { apiKeyRequest = null })
                        AppTab.MAGIC -> MagicSearchScreen(ar, magicSearch, scope,
                            onPlayStream = { u, t -> playingStream = BrowserStream(DetectedMedia(u, t, MediaKind.infer(u, null)), DownloadAuth.default) },
                            onOpenInBrowser = { u -> browserUrl = u; tab = AppTab.BROWSER }, onOpenDownloads = { tab = AppTab.DOWNLOADS })
                    }
                }
            }
            if (!locked) dubTarget?.let { v ->
                DubbingSheet(v, ar, onOpenSettings = { dubTarget = null; selectTab(AppTab.SETTINGS) },
                    onOpenGeminiSettings = { dubTarget = null; apiKeyRequest = "gemini"; selectTab(AppTab.SETTINGS) }) { dubTarget = null }
            }
        }
    }
}

@Composable
private fun LockScreen(ar: Boolean, onUnlock: () -> Unit) {
    val activity = LocalContext.current as android.app.Activity
    val app = activity.app()
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(V2.Bg)) {
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, null, tint = V2.Accent, modifier = Modifier.size(52.dp))
            Text(if (ar) "التطبيق مقفل" else "App is locked", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            OutlinedTextField(pw, { pw = it; error = false },
                placeholder = { Text(if (ar) "كلمة السر" else "Password") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
            if (error) Text(if (ar) "كلمة السر غير صحيحة" else "Wrong password", color = V2.Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = { if (app.settings.checkAppPassword(pw)) onUnlock() else error = true },
                enabled = pw.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (ar) "دخول" else "Unlock")
            }
            Spacer(Modifier.height(30.dp))
            Text(if (ar) "كادر" else "Kadr", color = V2.TextDim, fontSize = 11.sp)
        }
    }
}
