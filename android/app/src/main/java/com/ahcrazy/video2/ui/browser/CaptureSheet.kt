package com.ahcrazy.video2.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isYouTubePage
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.HlsVariant
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.theme.V2

internal data class CaptureMessage(val title: String, val description: String, val youtube: Boolean = false)

internal fun captureMessage(ar: Boolean, page: String, hint: PlaybackHint?, protected: Boolean, filteredCount: Int = 0): CaptureMessage {
    val youtube = httpMediaUrl(page)?.isYouTubePage() == true
    return when {
        protected -> CaptureMessage(if (ar) "المشغّل بيستخدم DRM" else "The player uses DRM",
            if (ar) "تقدر تشوف الفيديو في الموقع لو حسابك يسمح، لكن المصدر المحمي مش متاح للتحميل." else "Watch on the original website if you have access. Protected sources aren’t downloadable.", youtube)
        hint != null -> CaptureMessage(if (ar) "اتعرفنا على المشغّل" else "Player detected",
            if (hint == PlaybackHint.SEGMENTED || hint == PlaybackHint.IN_PAGE) {
                if (ar) "الفيديو شغّال جوه الصفحة، لكن المشغّل ما قدمش ملف كامل أو قائمة بث قابلة للحفظ. أجزاء التشغيل مش ملفات تنزيل." else "The video plays on the page, but the player hasn’t exposed a complete file or a stream we can save. Playback fragments aren’t downloads."
            } else if (ar) "الصفحة فيها مشغّل، لكن لسه مفيش رابط ملف أو بث صالح. لو الفيديو موقوف شغّله وبعدين اعمل فحص." else "The page has a player, but no usable file or stream link yet. If playback is paused, start it and scan again.", youtube)
        filteredCount > 0 -> CaptureMessage(if (ar) "اتخفى المحتوى الإعلاني" else "Ad sources were hidden",
            if (ar) "المصادر اللي ظهرت لحد دلوقتي اتصنّفت إعلانات. جرّب تشغّل الفيديو الأساسي." else "The detected sources were classified as ads. Play the main video and try again.")
        page.isBlank() -> CaptureMessage(if (ar) "افتح صفحة فيديو الأول" else "Open a video page first",
            if (ar) "اكتب عنوان الصفحة في المتصفح، أو استخدم التحميل المباشر لو عندك رابط ملف." else "Enter a page in the address bar, or use direct download if you already have a file link.")
        else -> CaptureMessage(if (ar) "لسه مفيش مصدر ظاهر" else "No media source yet",
            if (ar) "اضغط تشغيل على الفيديو جوه الصفحة وبعدين اضغط فحص هنا." else "Start the video on the page, then tap Scan here.", youtube)
    }
}

internal data class CaptureGroup(val key: Pair<String, String>, val items: List<DetectedMedia>)

/** Like the iOS radar, rank sources from the same page together instead of grouping by rotating CDN host. */
internal fun captureGroups(sources: List<DetectedMedia>): List<CaptureGroup> = sources.sortedByDescending(::mediaScore)
    .groupBy { (it.pageUrl ?: it.requestReferer ?: it.url) to it.title.trim().replace(Regex("\\s+"), " ").lowercase() }
    .map { (key, rows) -> CaptureGroup(key, rows) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureSheet(
    ar: Boolean, page: String, media: List<DetectedMedia>, message: CaptureMessage,
    checking: Boolean, quality: DetectedMedia?, preferredHeight: Int, recovering: Boolean,
    onClose: () -> Unit, onScan: () -> Unit, onVerify: (String?) -> Unit,
    onPlay: (DetectedMedia) -> Unit, onDownload: (DetectedMedia, HlsVariant?, Boolean) -> Unit,
    onQuality: (DetectedMedia?) -> Unit, onYoutubeHelp: () -> Unit
) {
    val host = httpMediaUrl(page)?.host
    val groups = remember(media) { captureGroups(media) }
    val checked = media.count { it.downloadChecked() && !it.drm.isProtected }
    val canCheckAlternatives = media.any { !it.drm.isProtected && it.probeProblem?.needsNewLink != true }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = V2.Card, contentColor = V2.Text) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(.83f).testTag("capture.sheet"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = V2.Mint.copy(alpha = .14f)) {
                        Icon(Icons.Filled.Radar, null, Modifier.padding(10.dp).size(24.dp), tint = V2.Mint)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(if (quality == null) (if (ar) "التقاط الوسائط" else "Media capture") else (if (ar) "جودات البث" else "Stream qualities"),
                            style = MaterialTheme.typography.titleLarge)
                        host?.let { Text(it, color = V2.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, if (ar) "إغلاق" else "Close") }
                }
            }
            if (quality != null) {
                item {
                    Text(quality.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (ar) "لو البث مباشر، هيتحفظ الجزء المتاح وقت التحميل فقط." else "For a live stream, only the available window is saved.", color = V2.TextDim)
                    TextButton(onClick = { onQuality(null) }) { Text(if (ar) "رجوع للمصادر" else "Back to sources") }
                }
                if (checking) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (!quality.canDownload || !quality.downloadChecked()) item {
                    Text(quality.probeProblem?.text(ar) ?: if (ar) "البث مش متاح للحفظ." else "This stream can’t be saved.", color = V2.Gold)
                    if (!quality.drm.isProtected) TextButton(onClick = { onVerify(quality.url) }) { Text(if (ar) "أعد التحقق" else "Check again") }
                } else {
                    item {
                        Button(onClick = { onDownload(quality, null, false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (preferredHeight > 0) (if (ar) "تحميل حسب تفضيلك ($preferredHeight p)" else "Download at your preference ($preferredHeight p)")
                              else if (ar) "تحميل تلقائي" else "Download automatically")
                        }
                    }
                    if (preferredHeight > 0) item { TextButton(onClick = { onDownload(quality, null, true) }) { Text(if (ar) "اختار أعلى جودة تلقائيًا" else "Use highest quality") } }
                    items(quality.variants, key = { it.id }) { v ->
                        OutlinedButton(onClick = { onDownload(quality, v, false) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(v.qualityLabel)
                        }
                    }
                }
            } else {
                if (recovering) item {
                    Surface(shape = RoundedCornerShape(14.dp), color = V2.CardSoft) {
                        Text(if (ar) "رجعت لصفحة المصدر. افحص بديل متاح، أو اقفل القائمة وشغّل الفيديو لو محتاج رابط جديد."
                            else "Back on the source page. Check another available source, or close this sheet and play the video for a fresh link.",
                            Modifier.padding(12.dp), color = V2.TextDim)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = onScan) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (ar) "فحص الصفحة" else "Scan page")
                        }
                        if (canCheckAlternatives) {
                            TextButton(onClick = { onVerify(null) }, enabled = !checking) {
                                Text(if (recovering) (if (ar) "فحص بدائل" else "Check alternatives") else (if (ar) "تحقق من الروابط" else "Check links"))
                            }
                        }
                    }
                    if (checking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(if (ar) "جارٍ التحقق من ردّ المصدر…" else "Checking source responses…", color = V2.TextDim)
                    } else if (media.isNotEmpty()) {
                        Text(if (ar) "${media.size} مصدر · $checked تم التحقق" else "${media.size} sources · $checked checked",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = V2.TextDim)
                    }
                }
                if (groups.isEmpty()) item { CaptureMessagePanel(ar, message, onYoutubeHelp) }
                items(groups, key = { "${it.key.first.length}:${it.key.first}${it.key.second}" }) { group ->
                    GroupCard(ar, group, checking, onPlay, onDownload, onQuality, onVerify)
                }
            }
        }
    }
}

@Composable
internal fun CaptureMessagePanel(ar: Boolean, message: CaptureMessage, onYoutubeHelp: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = V2.CardSoft) {
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Info, null, tint = V2.Mint)
            Text(message.title, style = MaterialTheme.typography.titleMedium)
            Text(message.description, style = MaterialTheme.typography.bodyMedium, color = V2.TextDim)
            if (message.youtube) TextButton(onClick = onYoutubeHelp) { Text(if (ar) "طريقة حفظ فيديوهاتك من يوتيوب" else "How to save your own YouTube videos") }
        }
    }
}

@Composable
private fun GroupCard(
    ar: Boolean, group: CaptureGroup, checking: Boolean, onPlay: (DetectedMedia) -> Unit,
    onDownload: (DetectedMedia, HlsVariant?, Boolean) -> Unit, onQuality: (DetectedMedia?) -> Unit, onVerify: (String?) -> Unit
) {
    var expanded by rememberSaveable(group.key.first, group.key.second) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.items.take(if (expanded) group.items.size else 1).forEach { m ->
            key(m.url) { SourceCard(m, ar, checking, onPlay, onDownload, onQuality, onVerify) }
        }
        if (group.items.size > 1) TextButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp))
            Text(if (expanded) (if (ar) "إخفاء باقي المصادر" else "Hide other sources")
                else if (ar) "مصادر تانية للصفحة (${group.items.size - 1})" else "Other sources on this page (${group.items.size - 1})")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    m: DetectedMedia, ar: Boolean, checking: Boolean, onPlay: (DetectedMedia) -> Unit,
    onDownload: (DetectedMedia, HlsVariant?, Boolean) -> Unit, onQuality: (DetectedMedia?) -> Unit, onVerify: (String?) -> Unit
) {
    val ready = m.downloadChecked()
    val status = when {
        m.drm.isProtected -> (if (ar) "محمي بـ DRM" else "DRM protected") to V2.Accent
        m.probeProblem != null -> (if (ar) "تعذّر التحقق" else "Check failed") to V2.Gold
        m.kind == MediaKind.DASH -> (if (ar) "تشغيل فقط" else "Playback only") to V2.Gold
        m.extractionMethod == "webview-download" -> (if (ar) "رابط تنزيل من الموقع" else "Download offered by the site") to V2.Mint
        ready -> (if (ar) "اتحققنا من المصدر" else "Source checked") to V2.Mint
        else -> (if (ar) "مصدر مكتشف" else "Source detected") to V2.Sky
    }
    Surface(shape = RoundedCornerShape(18.dp), color = V2.CardSoft) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MediaPill(status.first, status.second)
                if (m.kind != MediaKind.OTHER) MediaPill(if (ar) m.kind.titleAr else m.kind.titleEn, V2.Sky)
                (m.qualityLabel ?: m.height?.let { "${it}p" })?.let { MediaPill(it, V2.Mint) }
                if (m.durationSec != null) MediaPill(m.durationText, V2.TextDim)
                m.byteSize?.let { MediaPill(L10n.fmtBytes(it), V2.TextDim) }
            }
            Text(httpMediaUrl(m.url)?.host.orEmpty(), color = V2.TextDim, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            m.probeProblem?.let { problem ->
                val statusCode = m.probeHttpStatus?.let { " (HTTP $it)" }.orEmpty()
                Text(problem.text(ar) + statusCode, color = V2.Gold, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val offersDownload = m.canDownload && m.kind != MediaKind.OTHER
                if (offersDownload) Button(enabled = !checking, onClick = {
                    when {
                        m.kind == MediaKind.HLS -> onQuality(m)
                        ready -> onDownload(m, null, false)
                        else -> onVerify(m.url)
                    }
                }) {
                    Icon(if (ready) Icons.Filled.Download else Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                    Text(when {
                        m.kind == MediaKind.HLS -> if (ar) "تحميل وجودة" else "Download / quality"
                        ready -> if (ar) "تحميل" else "Download"
                        else -> if (ar) "تحقق للتحميل" else "Check for download"
                    })
                }
                if (m.canPlay) OutlinedButton(onClick = { onPlay(m) }) { Text(if (ar) "تشغيل" else "Play") }
                if (!offersDownload && !m.drm.isProtected && !ready) TextButton(enabled = !checking, onClick = { onVerify(m.url) }) {
                    Text(if (m.probeProblem != null) (if (ar) "أعد التحقق" else "Check again") else (if (ar) "تحقق" else "Check"))
                }

            }
        }
    }
}

@Composable
private fun MediaPill(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = .13f), contentColor = tint) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
