package com.ahcrazy.video2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Captions
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.browser.BrowserScreen
import com.ahcrazy.video2.ui.downloads.DownloadsScreen
import com.ahcrazy.video2.ui.library.LibraryScreen
import com.ahcrazy.video2.ui.magic.MagicSearchScreen
import com.ahcrazy.video2.ui.player.PlayerScreen
import com.ahcrazy.video2.ui.settings.SettingsScreen
import com.ahcrazy.video2.ui.theme.V2
import com.ahcrazy.video2.ui.theme.Video2Theme
import com.ahcrazy.video2.ui.translate.ConversionScreen
import com.ahcrazy.video2.ui.translate.DubbingSheet
import com.ahcrazy.video2.ui.translate.SubtitleReviewScreen
import com.ahcrazy.video2.ui.translate.TranslateScreen
import kotlinx.coroutines.launch

private data class TabDef(val index: Int, val key: String, val icon: ImageVector)

@Composable
fun Video2Root() {
    val activity = LocalContext.current as android.app.Activity
    val app = activity.app()
    val lang by app.settings.lang.collectAsState(initial = "ar")
    val ar = lang != "en"
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(app.settings.hasAppPassword()) }
    var playingVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var playingStream by remember { mutableStateOf<Pair<String, String>?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var translatePreselect by remember { mutableStateOf<SavedVideo?>(null) }
    var reviewJob by remember { mutableStateOf<TranslationJob?>(null) }
    var dubTarget by remember { mutableStateOf<SavedVideo?>(null) }
    var showConvert by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tabs = listOf(
        TabDef(0, "tab.browser", Icons.Filled.Explore),
        TabDef(1, "tab.library", Icons.Filled.PlayCircle),
        TabDef(2, "tab.translate", Icons.Filled.Captions),
        TabDef(3, "tab.downloads", Icons.Filled.CloudDownload),
        TabDef(4, "tab.settings", Icons.Filled.Settings),
        TabDef(5, "tab.magic", Icons.Filled.AutoAwesome)
    )

    Video2Theme(rtl = ar) {
        Box(Modifier.fillMaxSize().background(V2.Bg)) {
            when {
                playingVideo != null || playingStream != null -> PlayerScreen(
                    video = playingVideo, streamUrl = playingStream?.first, streamTitle = playingStream?.second, ar = ar,
                    onBack = { playingVideo = null; playingStream = null })
                reviewJob != null -> SubtitleReviewScreen(reviewJob!!, ar) { reviewJob = null }
                showConvert -> ConversionScreen(ar) { showConvert = false }
                else -> {
                    Box(Modifier.fillMaxSize().padding(bottom = 62.dp)) {
                        // Keep every tab alive (like the iOS custom tab bar) — only visibility toggles.
                        TabHost(visible = tab == 0) {
                            BrowserScreen(ar, browserUrl, { browserUrl = null }, onPlayStream = { u, t -> playingStream = u to t })
                        }
                        TabHost(visible = tab == 1) {
                            LibraryScreen(ar,
                                onPlay = { playingVideo = it },
                                onTranslate = { translatePreselect = it; tab = 2 },
                                onReview = { v ->
                                    app.translations.jobs.value.firstOrNull { it.videoId == v.id }?.let { reviewJob = it }
                                },
                                onDub = { dubTarget = it },
                                onConvert = { showConvert = true })
                        }
                        TabHost(visible = tab == 2) {
                            TranslateScreen(ar, translatePreselect, { translatePreselect = null }, onReviewJob = { reviewJob = it })
                        }
                        TabHost(visible = tab == 3) { DownloadsScreen(ar) }
                        TabHost(visible = tab == 4) {
                            SettingsScreen(ar, onLangChange = { code -> scope.launch { app.settings.setLang(code) } },
                                onLockNow = { locked = true })
                        }
                        TabHost(visible = tab == 5) {
                            MagicSearchScreen(ar,
                                onPlayStream = { u, t -> playingStream = u to t },
                                onOpenInBrowser = { u -> browserUrl = u; tab = 0 })
                        }
                    }
                    // Custom 6-tab bar — never a system "More" tab (same fix as iOS)
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(62.dp)
                        .background(V2.Card.copy(alpha = .98f)).padding(horizontal = 2.dp, vertical = 6.dp)) {
                        tabs.forEach { def ->
                            val active = tab == def.index
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .clickable { tab = def.index }.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(def.icon, null, tint = if (active) V2.Accent else V2.TextDim, modifier = Modifier.size(21.dp))
                                Text(L10n.t(lang, def.key), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (active) V2.Accent else V2.TextDim)
                                if (active) Box(Modifier.padding(top = 2.dp).size(5.dp).clip(RoundedCornerShape(50)).background(V2.Accent))
                            }
                        }
                    }
                }
            }

            dubTarget?.let { v -> DubbingSheet(v, ar) { dubTarget = null } }

            if (locked) LockScreen(ar, onUnlock = { locked = false })
        }
    }
}

@Composable
private fun TabHost(visible: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().then(if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics { })) { content() }
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
            Text("فيديو ٢ · Video2", color = V2.TextDim, fontSize = 11.sp)
        }
    }
}
