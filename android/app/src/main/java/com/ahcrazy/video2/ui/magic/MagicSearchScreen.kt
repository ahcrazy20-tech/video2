package com.ahcrazy.video2.ui.magic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.core.util.MagicQuery
import com.ahcrazy.video2.data.MagicResult
import com.ahcrazy.video2.data.PeerTubeVideo
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.ui.browser.ExternalDownloadSheet
import com.ahcrazy.video2.ui.browser.ExternalVideoChoice
import com.ahcrazy.video2.ui.browser.SmartDownloadDialog
import com.ahcrazy.video2.ui.components.ChipPreview
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val sourceLabels = mapOf(
    "archive" to ("أرشيف الإنترنت" to "🏛"), "youtube" to ("يوتيوب" to "▶️"),
    "dailymotion" to ("داليموشن" to "🎬"), "peertube" to ("PeerTube" to "📺"),
    "vimeo" to ("فيميو" to "🎞"), "web" to ("كل الويب" to "🌐")
)

internal class MagicSearchState {
    var text by mutableStateOf("")
    var searching by mutableStateOf(false)
    var ranOnce by mutableStateOf(false)
    var deepHunt by mutableStateOf(true)
    var autoPrepare by mutableStateOf(true)
    var activeSource by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    val results = mutableStateMapOf<String, List<MagicResult>>()
}

@Composable
internal fun MagicSearchScreen(
    ar: Boolean, state: MagicSearchState, scope: CoroutineScope,
    onPlayStream: (String, String) -> Unit, onOpenInBrowser: (String) -> Unit, onOpenDownloads: () -> Unit
) {
    val app = (LocalContext.current as android.app.Activity).app()
    val jobs by app.downloads.jobs.collectAsState()
    var showSyntax by remember { mutableStateOf(false) }
    var smart by remember { mutableStateOf<SmartDownloadRequest?>(null) }
    var external by remember { mutableStateOf<ExternalVideoChoice?>(null) }
    val preferredHeight by app.settings.downloadMaxHeight.collectAsState(initial = 0)

    fun runSearch() {
        val q = MagicQuery.parse(state.text)
        if (q.directUrl != null) { onOpenInBrowser(q.directUrl); return }
        if (q.isEmpty || state.searching) return
        state.results.clear(); state.searching = true; state.ranOnce = true
        scope.launch {
            try { app.magic.searchAll(q) { src, list -> state.results[src] = list } }
            finally { state.searching = false }
        }
    }

    val visible = state.results.filter { state.activeSource == null || it.key == state.activeSource }
    val total = state.results.values.sumOf { it.size }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = V2.Accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ar) "البحث السحري" else "Magic Search", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "6 مصادر في نفس الوقت — تشغيل وتحميل من هنا على طول" else "6 sources at once — play & download right here", fontSize = 11.sp, color = V2.TextDim)
            }
            IconButton(onClick = { showSyntax = true }) { Icon(Icons.Filled.HelpOutline, null, tint = V2.Gold) }
        }
        OutlinedTextField(state.text, { state.text = it },
            placeholder = { Text(if (ar) "اسم فيلم أو فيديو… أو الصق رابط" else "Movie or video name… or paste a link", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("+5د min:5", "+20د min:20", "+60د min:60", "HD جودة:1080").forEach { chip ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(V2.Card).clickable {
                    state.text = (state.text.trim() + " " + chip.substringAfter(" ")).trim(); runSearch()
                }.padding(horizontal = 11.dp, vertical = 6.dp)) {
                    Text(chip, fontSize = 11.sp, color = V2.Gold, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { runSearch() }, enabled = state.text.isNotBlank()) { Text(if (ar) "ابحث" else "Search", fontSize = 12.sp) }
        }
        Text(if (ar) "أوامر: مدة:1:36:00 · جودة:1080 · موقع:site.com · استبعد:trailer · سنة:1995 · مصدر:archive · ترتيب:views" else "Commands: duration:1:36:00 · quality:1080 · site:site.com · exclude:trailer · year:1995 · source:archive · sort:views",
            fontSize = 10.sp, color = V2.TextDim, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ar) "الصيد العميق" else "Deep Hunt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "بيفتح الصفحة في متصفح خفي ويدوس Play لحد ما الرابط يظهر" else "Hidden browser taps Play until the stream appears", fontSize = 10.sp, color = V2.TextDim)
            }
            Switch(state.deepHunt, { state.deepHunt = it })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ar) "تحضير مبكر" else "Auto-prepare", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(if (ar) "تحميل العناصر الكسولة فورًا" else "Eager-load lazy media", fontSize = 10.sp, color = V2.TextDim)
            }
            Switch(state.autoPrepare, { state.autoPrepare = it })
        }
        // Source filter chips with live counts
        if (state.results.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(if (ar) "الكل" else "All", total, state.activeSource == null) { state.activeSource = null }
                state.results.forEach { (src, list) ->
                    SourceChip("${sourceLabels[src]?.second ?: ""} ${list.size}", list.size, state.activeSource == src) {
                        state.activeSource = if (state.activeSource == src) null else src
                    }
                }
            }
        }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = V2.Accent)
        state.toast?.let { Text(it, color = V2.Mint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }

        if (!state.ranOnce) EmptyState(Icons.Filled.AutoAwesome, if (ar) "دور في كل حتة مرة واحدة" else "Search everywhere at once",
            if (ar) "أرشيف الإنترنت (أفلام ملكية عامة) · يوتيوب · داليموشن · PeerTube · فيميو · الويب كله" else "Internet Archive · YouTube · Dailymotion · PeerTube · Vimeo · the whole web")
        else if (!state.searching && total == 0) EmptyState(Icons.Filled.Search, if (ar) "مفيش نتائج" else "No results",
            if (ar) "جرب اسم تاني أو شيل فلاتر المدة/السنة" else "Try another name or drop duration/year filters")

        LazyColumn(Modifier.weight(1f)) {
            visible.forEach { (src, list) ->
                if (list.isEmpty()) return@forEach
                item {
                    Text("${sourceLabels[src]?.second} ${if (ar) sourceLabels[src]?.first else src} · ${list.size}",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                }
                items(list) { r ->
                    val watch = YouTubeVideo.parse(r.pageUrl)
                    val peerTube = if (r.source == "peertube") PeerTubeVideo.parse(r.pageUrl) else null
                    ResultCard(r, ar, MagicQuery.parse(state.text),
                        externalDownload = watch != null || peerTube != null,
                        downloadProgress = jobs.firstOrNull { it.media.pageUrl == r.pageUrl || it.media.url == r.pageUrl ||
                            watch != null && YouTubeVideo.fromMedia(it.media) == watch },
                        onPlay = {
                            val direct = r.playableUrl
                            if (r.source == "archive" && direct != null) {
                                state.toast = if (ar) "جارٍ جلب جودات الأرشيف…" else "Fetching archive qualities…"
                                onOpenInBrowser(direct)
                            } else if (state.deepHunt || true) {
                                state.toast = if (ar) "🕵️ الصيد العميق بيفتح الصفحة في المتصفح…" else "🕵️ Deep Hunt is opening the page…"
                                onOpenInBrowser(r.pageUrl)
                            }
                        },
                        onOpen = { onOpenInBrowser(r.pageUrl) },
                        onDownload = {
                            if (watch != null || peerTube != null) smart = SmartDownloadRequest(watch, r.title, peerTube = peerTube)
                            else onOpenInBrowser(r.pageUrl) // A watch page is not a downloadable file. Use its capture/download UI.
                        })
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    smart?.let { request ->
        val height = MagicQuery.parse(state.text).preferredHeight ?: preferredHeight
        SmartDownloadDialog(ar, request, height, onClose = { smart = null }, onQueued = { smart = null; onOpenDownloads() },
            onManual = request.video?.let { video -> { smart = null; external = ExternalVideoChoice(video, request.title) } })
    }

    external?.let { choice ->
        val height = MagicQuery.parse(state.text).preferredHeight ?: preferredHeight
        ExternalDownloadSheet(ar, choice, height, onClose = { external = null }, onDownload = { media ->
            app.downloads.enqueue(media, maxHeight = height.takeIf { it > 0 })
            state.toast = if (ar) "اتضاف لقائمة التحميلات" else "Added to Downloads"
            external = null
        })
    }

    if (showSyntax) AlertDialog(onDismissRequest = { showSyntax = false },
        title = { Text(if (ar) "صيغة البحث السحري" else "Magic query syntax") },
        text = { Text(
            "اسم الفيلم مدة:1:36:00 جودة:1080 موقع:site.com استبعد:trailer سنة:1995 مصدر:archive ترتيب:views min:20 max:90\n\n" +
            "• الأوامر بالعربي أو الإنجليزي (duration/quality/site/exclude/year/source/sort)\n" +
            "• الأرقام العربية شغالة (١:٣٦) وصيغ زي 2h28m أو 148 (= دقائق)\n" +
            "• \"عبارة بين علامتين\" = لازم تظهر في العنوان\n" +
            "• الصق أي رابط صفحة وهيتصاد منه الفيديو فورًا", fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = { showSyntax = false }) { Text(if (ar) "فهمت" else "Got it") } })
}

@Composable private fun SourceChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (active) V2.Accent else V2.Card).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (active) V2.Bg else V2.TextDim)
    }
}

@Composable
private fun ResultCard(r: MagicResult, ar: Boolean, q: MagicQuery,
                       downloadProgress: com.ahcrazy.video2.core.model.DownloadJob?, externalDownload: Boolean,
                       onPlay: () -> Unit, onOpen: () -> Unit, onDownload: () -> Unit) {
    val match = MagicDuration.matchOf(r.durationSec, q)
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row {
            if (r.thumbnail != null) AsyncImage(r.thumbnail, null,
                Modifier.width(118.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(V2.CardSoft))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (r.durationSec != null) ChipPreview("⏱ ${MagicDuration.format(r.durationSec)}", V2.TextDim)
                    if (r.views != null && r.views > 0) ChipPreview("👁 ${compactViews(r.views)}", V2.TextDim)
                    when (match) {
                        MagicDuration.Match.MATCH -> ChipPreview("✓ مطابق للمدة", V2.Mint)
                        MagicDuration.Match.NEAR -> ChipPreview("≈ قريب", V2.Gold)
                        MagicDuration.Match.TRUNCATED -> ChipPreview("✂ مبتور؟", V2.Accent)
                        MagicDuration.Match.UNKNOWN -> {}
                    }
                }
                r.snippet?.let { Text(it, fontSize = 11.sp, color = V2.TextDim, maxLines = 2, modifier = Modifier.padding(top = 5.dp)) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, null, Modifier.width(15.dp)); Text(if (ar) "تشغيل" else "Play", fontSize = 11.sp) }
                    if (!externalDownload) OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, null, Modifier.width(15.dp)); Text(if (ar) "تحميل" else "Get", fontSize = 11.sp)
                    }
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.OpenInBrowser, null, tint = V2.TextDim) }
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.Radar, null, tint = V2.Mint) }
                }
                if (externalDownload) TextButton(onClick = onDownload) {
                    Icon(Icons.Filled.CloudDownload, null, Modifier.width(17.dp), tint = V2.Mint)
                    Spacer(Modifier.width(5.dp))
                    Text(if (ar) "تحميل ذكي" else "Smart download")
                }
                downloadProgress?.let { j ->
                    LinearProgressIndicator(progress = { j.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 6.dp), color = V2.Mint)
                    Text("${j.state.name} · ${(j.progress * 100).toInt()}%", fontSize = 10.sp, color = V2.Mint)
                }
            }
        }
    }
}

private fun compactViews(v: Long): String = when {
    v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
    v >= 1_000 -> String.format("%.1fK", v / 1_000.0)
    else -> "$v"
}
