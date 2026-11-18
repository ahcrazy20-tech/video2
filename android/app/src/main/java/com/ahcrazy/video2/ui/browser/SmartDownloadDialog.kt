package com.ahcrazy.video2.ui.browser

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.MAX_AUTO_RATE_WAIT_MS
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.data.*
import com.ahcrazy.video2.ui.components.cooldownSeconds
import com.ahcrazy.video2.ui.components.formatCooldown
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.IOException

private sealed interface DownloadAction {
    data object Automatic : DownloadAction
    data object ListQualities : DownloadAction
    data class Exact(val quality: SmartQuality) : DownloadAction
}

@Composable
internal fun SmartDownloadDialog(
    ar: Boolean, request: SmartDownloadRequest, preferredHeight: Int,
    onClose: () -> Unit, onQueued: () -> Unit, onManual: (() -> Unit)? = null,
    onDirectChecked: (DetectedMedia) -> Unit = {},
) {
    val app = (LocalContext.current as Activity).app()
    val queued by rememberUpdatedState(onQueued)
    val checked by rememberUpdatedState(onDirectChecked)
    var action by remember(request) { mutableStateOf<DownloadAction?>(null) }
    var alreadyDownloading by remember(request) { mutableStateOf(app.downloads.activeSmartJob(request) != null) }
    var choices by remember(request) { mutableStateOf(emptyList<SmartQuality>()) }
    var selectedKey by remember(request) { mutableStateOf<String?>(null) }
    var attempt by remember(request) { mutableIntStateOf(0) }
    val rejected = remember(request) { request.excludedUrls.toMutableSet() }
    val answer = remember(request, attempt) { CompletableDeferred<Unit>() }
    var awaitingConsent by remember(request, attempt) { mutableStateOf(false) }
    var failure by remember(request, attempt) { mutableStateOf<SmartResult.Unavailable?>(null) }
    var stage by remember(request, attempt) { mutableStateOf(SmartStep(SmartPhase.DIRECT)) }
    var automaticRetry by remember(request, attempt) { mutableStateOf(false) }
    val choice = choices.firstOrNull { it.key == selectedKey }

    fun start(next: DownloadAction) {
        action = next
        attempt++
    }

    LaunchedEffect(request, attempt) {
        val task = action ?: return@LaunchedEffect
        if (app.downloads.activeSmartJob(request) != null) { alreadyDownloading = true; return@LaunchedEffect }
        for (pass in 0..1) {
            failure = null
            automaticRetry = false
            stage = SmartStep(SmartPhase.DIRECT)
            val result = try {
                if (task is DownloadAction.Exact) {
                    // A list opened with permission must not keep that permission after revocation.
                    if (request.video != null && task.quality.candidate.auth.externalHttpsOnly && !app.settings.smartServersAllowed.first()) {
                        choices = emptyList()
                        action = null
                        return@LaunchedEffect
                    }
                    app.smartDownloads.verify(task.quality, progress = { stage = it })
                } else app.smartDownloads.find(request.copy(excludedUrls = rejected.toSet()), preferredHeight, permitServers = {
                    if (app.settings.smartServersAllowed.first()) true
                    else if (pass == 0) {
                        awaitingConsent = true
                        answer.await()
                        awaitingConsent = false
                        app.settings.setSmartServersAllowed(true)
                        true
                    } else false
                }, progress = { stage = it }, onDirectChecked = { checked(it) }, onRejectedUrl = { rejected += it },
                    offerQualities = task == DownloadAction.ListQualities)
            } catch (_: IOException) { SmartResult.Unavailable(media = MediaProblem.NETWORK, step = stage) }
            ensureActive()
            when (result) {
                is SmartResult.Ready -> {
                    if (task == DownloadAction.ListQualities) {
                        choices = result.qualities
                        selectedKey = choices.firstOrNull { it.isChecked() }?.key ?: choices.firstOrNull()?.key
                        action = null
                    } else {
                        val height = (task as? DownloadAction.Exact)?.quality?.height ?: preferredHeight.takeIf { it > 0 }
                        val added = app.downloads.enqueueSmart(result.candidate, request, height)
                        if (task is DownloadAction.Exact && !task.quality.matches(added.media)) alreadyDownloading = true
                        else queued()
                    }
                    return@LaunchedEffect
                }
                is SmartResult.Unavailable -> {
                    failure = result
                    if (task is DownloadAction.Exact && result.media?.needsNewLink == true) {
                        choices = choices.filterNot { it.key == task.quality.key }
                        // A refused file cannot be chosen again during the same dialog. A missing HLS rendition does not ban the whole master.
                        if (task.quality.rendition == null) rejected += task.quality.media.url
                        selectedKey = choices.firstOrNull()?.key
                    }
                    val wait = result.cooldown?.untilMs?.minus(System.currentTimeMillis()) ?: 0L
                    automaticRetry = task !is DownloadAction.Exact && pass == 0 && result.step?.phase != SmartPhase.DIRECT && wait in 1..MAX_AUTO_RATE_WAIT_MS
                    if (!automaticRetry) break
                    delay(wait)
                }
            }
        }
    }

    val failed = failure
    val seconds = cooldownSeconds(failed?.cooldown?.untilMs)
    val showQualities = !alreadyDownloading && action == null && choices.isNotEmpty()
    val mayRetry = failed != null && !failed.protected && failed.resolver != ResolverProblem.ACCESS && failed.media != MediaProblem.SIGN_IN && failed.httpStatus != 401
    val refreshChoices = action is DownloadAction.Exact && failed?.media?.needsNewLink == true
    val backToChoices = action is DownloadAction.Exact && failed != null && choices.isNotEmpty() && mayRetry
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("smartDownload.dialog"),
        containerColor = V2.Card, titleContentColor = V2.Text, textContentColor = V2.Text,
        icon = {
            Box(Modifier.size(50.dp).background(V2.Mint.copy(alpha = .13f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(if (showQualities) Icons.Filled.HighQuality else Icons.Filled.CloudDownload, null, tint = V2.Mint)
            }
        },
        title = { Text(if (ar) (if (showQualities) "الجودات المتاحة" else "اختيارات التنزيل") else (if (showQualities) "Available qualities" else "Download options")) },
        text = {
            Column(Modifier.verticalScroll(key(action, awaitingConsent, failed) { rememberScrollState() }),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ((action as? DownloadAction.Exact)?.quality?.media?.title ?: request.title)?.takeIf(String::isNotBlank)?.let { title ->
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    alreadyDownloading -> Text(if (ar) "الفيديو ده بيتحمّل بالفعل. مش هنبدأ نسخة تانية." else "This video is already downloading. We won’t start a second copy.")
                    awaitingConsent -> SmartServerDisclosure(ar)
                    failed != null -> {
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(failed.text(ar, request.peerTube != null), color = V2.Gold)
                            failed.step?.let { where ->
                                Text(where.phase.label(ar), color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                                where.host?.let {
                                    Text(it, color = V2.Sky, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (seconds > 0) {
                                val time = formatCooldown(seconds, ar)
                                Text(if (automaticRetry) {
                                    if (ar) "هنعيد المحاولة مرة بعد $time لو النافذة فضلت مفتوحة." else "One retry in $time if you keep this open."
                                } else if (ar) "المحاولة متاحة بعد $time." else "You can retry in $time.", color = V2.TextDim,
                                    modifier = Modifier.testTag("smartDownload.cooldown"))
                            }
                        }
                        if (onManual != null && mayRetry && failed.httpStatus != 429) TextButton(onClick = onManual) {
                            Text(if (ar) "تحميل بخادمك (يدوي)" else "Use your own server (manual)")
                        }
                    }
                    showQualities -> {
                        Text(if (ar) "اختار نسخة. هنتأكد إنها شغالة قبل ما يبدأ التحميل." else "Pick a rendition. Video2 checks it before starting the download.",
                            color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                        DownloadQualityRows(ar, choices, selectedKey) { selectedKey = it }
                    }
                    action == null -> {
                        if (request.peerTube != null) Text(if (ar) "النسخ المنشورة للتنزيل من موقع الفيديو نفسه." else "Uses downloads published by the site hosting the video.",
                            color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                        DownloadIntentCards(ar, preferredHeight,
                            onAutomatic = { start(DownloadAction.Automatic) }, onQualities = { start(DownloadAction.ListQualities) })
                    }
                    else -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = V2.Mint)
                        Text(stage.text(ar), color = V2.Text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        },
        confirmButton = {
            when {
                alreadyDownloading -> Button(onClick = onQueued, colors = downloadButtonColors()) {
                    Text(if (ar) "افتح التحميلات" else "Open Downloads")
                }
                awaitingConsent -> Button(onClick = { answer.complete(Unit) }, modifier = Modifier.testTag("smartDownload.enable")) {
                    Text(if (ar) "فعّل وكمّل" else "Enable & continue")
                }
                showQualities -> Button(enabled = choice != null, onClick = { choice?.let { start(DownloadAction.Exact(it)) } },
                    modifier = Modifier.testTag("smartDownload.confirmQuality"), colors = downloadButtonColors()) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(choice?.height?.let { if (ar) "تنزيل ${it}p" else "Download ${it}p" } ?: if (ar) "تنزيل" else "Download")
                }
                mayRetry -> Button(enabled = seconds == 0L && !automaticRetry, modifier = Modifier.testTag("smartDownload.retry"),
                    colors = downloadButtonColors(), onClick = {
                        if (refreshChoices) start(DownloadAction.ListQualities)
                        else action?.let(::start)
                    }) {
                    Text(if (refreshChoices) (if (ar) "حدّث الجودات" else "Refresh qualities") else if (ar) "حاول تاني" else "Try again")
                }
                else -> TextButton(onClick = onClose) { Text(if (ar) "إغلاق" else "Close") }
            }
        },
        dismissButton = {
            when {
                alreadyDownloading -> TextButton(onClick = onClose) { Text(if (ar) "إغلاق" else "Close") }
                awaitingConsent -> TextButton(onClick = onClose) { Text(if (ar) "مش دلوقتي" else "Not now") }
                showQualities -> TextButton(onClick = { choices = emptyList(); selectedKey = null }) { Text(if (ar) "رجوع" else "Back") }
                backToChoices -> TextButton(onClick = { failure = null; action = null }) { Text(if (ar) "الجودات" else "Qualities") }
                mayRetry -> TextButton(onClick = onClose) { Text(if (ar) "إغلاق" else "Close") }
            }
        })
}

@Composable
internal fun DownloadIntentCards(ar: Boolean, height: Int, onAutomatic: () -> Unit, onQualities: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadModeCard(Icons.Filled.AutoAwesome, if (ar) "تحميل تلقائي" else "Automatic download",
            if (height > 0) (if (ar) "تفضيل ${height}p · يختار المصدر بنفسه" else "Prefers ${height}p · picks a working source")
            else if (ar) "يختار مصدر شغّال ويبدأ فور التحقق" else "Checks a working source and starts", V2.Mint,
            "smartDownload.auto", onAutomatic)
        DownloadModeCard(Icons.Filled.Tune, if (ar) "اختار الجودة" else "Choose quality",
            if (ar) "اعرض النسخ المتاحة قبل التنزيل" else "See the available renditions first", V2.Sky,
            "smartDownload.qualities", onQualities)
    }
}

@Composable
private fun DownloadModeCard(icon: ImageVector, title: String, detail: String, tint: Color, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = .08f), border = BorderStroke(1.dp, tint.copy(alpha = .24f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).background(tint.copy(alpha = .13f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = V2.Text, style = MaterialTheme.typography.titleSmall)
                Text(detail, color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun DownloadQualityRows(ar: Boolean, options: List<SmartQuality>, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { quality ->
            key(quality.key) {
                val active = selected == quality.key
                val label = quality.height?.let { "${it}p" } ?: quality.rendition?.takeIf { it.bandwidth > 0 }?.qualityLabel
                    ?: if (ar) "جودة المصدر" else "Source quality"
                val media = quality.media
                val method = when {
                    media.joinedTracks != null -> if (ar) "MP4 · دمج الصورة والصوت" else "MP4 · joins video and audio"
                    media.kind == MediaKind.HLS -> "HLS"
                    else -> media.kind.titleEn
                }
                val details = listOfNotNull(method, media.byteSize?.let(L10n::fmtBytes),
                    if (quality.isChecked()) (if (ar) "تم التحقق" else "Checked") else null).joinToString(" · ")
                Surface(shape = RoundedCornerShape(13.dp), color = if (active) V2.Mint.copy(alpha = .1f) else V2.CardSoft,
                    border = BorderStroke(1.dp, if (active) V2.Mint.copy(alpha = .7f) else V2.Divider)) {
                    Row(Modifier.fillMaxWidth().selectable(selected = active, onClick = { onSelect(quality.key) }, role = Role.RadioButton)
                        .testTag("smartDownload.quality.${quality.height ?: 0}").padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 42.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = if (active) V2.Mint else V2.Text, style = MaterialTheme.typography.titleMedium)
                            Text(details, color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) Icon(Icons.Filled.CheckCircle, null, tint = V2.Mint, modifier = Modifier.size(20.dp))
                        else Box(Modifier.size(18.dp).background(V2.Outline.copy(alpha = .23f), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable private fun downloadButtonColors() = ButtonDefaults.buttonColors(containerColor = V2.Mint, contentColor = V2.Bg)

@Composable
internal fun SmartServerDisclosure(ar: Boolean) {
    val uriHandler = LocalUriHandler.current
    Text(if (ar) "لو مفيش مصدر مباشر صالح، Video2 هيجرب خوادم Invidious العامة دي."
        else "If no direct source works, Video2 tries these public Invidious servers.")
    Text(SmartServers.endpoints.joinToString("\n") { it.host }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = V2.Sky)
    Text(if (ar) "الخادم بيستقبل معرّف فيديو YouTube وبيشوف عنوان اتصالك، مش الكوكيز ولا تسجيل الدخول ولا رابط الوسائط الموقّع. التفعيل مرة واحدة للفيديوهات العامة المسموح لك تحفظها، وتقدر تلغيه من الإعدادات."
        else "The server sees your IP and the YouTube video ID, not your cookies, sign-in data, or signed media link. Enable once for public videos you're allowed to save, and turn it off in Settings anytime.", color = V2.TextDim)
    TextButton(onClick = { runCatching { uriHandler.openUri(SmartServers.privacy) } }) {
        Text(if (ar) "خصوصية الخادم" else "Server privacy policy")
    }
}

private fun SmartStep.text(ar: Boolean): String = when (phase) {
    SmartPhase.DIRECT -> if (ar) "بنتحقق من المصادر المباشرة…" else "Checking direct sources…"
    SmartPhase.LOOKUP -> if (ar) "بنجلب النسخ المتاحة…" else "Looking up available renditions…"
    SmartPhase.MEDIA -> if (ar) "بنتأكد من الوسائط قبل التحميل…" else "Checking media before download…"
}

private fun SmartPhase.label(ar: Boolean): String = when (this) {
    SmartPhase.DIRECT -> if (ar) "المرحلة: فحص المصدر المباشر" else "Stage: direct source check"
    SmartPhase.LOOKUP -> if (ar) "المرحلة: جلب رابط التحميل" else "Stage: server lookup"
    SmartPhase.MEDIA -> if (ar) "المرحلة: التحقق من الوسائط" else "Stage: media check"
}

private fun SmartResult.Unavailable.text(ar: Boolean, peerTube: Boolean): String {
    val detail = when {
        protected -> if (ar) "المصدر محمي أو مخصص للاشتراك، فالتحميل متوقف." else "This source is protected or restricted to subscribers."
        httpStatus == 429 -> if (ar) "المصدر طلب نوقف الطلبات مؤقتًا." else "The source asked us to pause requests."
        peerTube && resolver == ResolverProblem.ACCESS -> if (ar) "صاحب الفيديو أو الموقع مش مفعّل التنزيل العام." else "The uploader or site hasn't enabled public downloads."
        resolver == ResolverProblem.NETWORK -> if (ar) "الاتصال بخادم التحميل ما اكتملش. اتأكد من الإنترنت وحاول تاني." else "Couldn’t reach the download server. Check your connection and try again."
        resolver != null -> resolver.text(ar)
        media != null -> media.text(ar)
        else -> if (ar) "ملقيناش ملف فيديو متاح. افتح صفحة الفيديو أو شغّله وافحص تاني." else "No downloadable video found. Open the video page or play it and scan again."
    }
    return detail + (httpStatus?.let { " (HTTP $it)" } ?: "")
}
