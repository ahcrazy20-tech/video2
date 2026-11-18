package com.ahcrazy.video2.ui.browser

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.MAX_AUTO_RATE_WAIT_MS
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.PublisherLinks
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartPhase
import com.ahcrazy.video2.data.SmartResult
import com.ahcrazy.video2.data.SmartServers
import com.ahcrazy.video2.data.SmartStep
import com.ahcrazy.video2.ui.components.cooldownSeconds
import com.ahcrazy.video2.ui.components.formatCooldown
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.IOException

@Composable
internal fun SmartDownloadDialog(
    ar: Boolean, request: SmartDownloadRequest, preferredHeight: Int,
    onClose: () -> Unit, onQueued: () -> Unit, onManual: (() -> Unit)? = null,
    onDirectChecked: (DetectedMedia) -> Unit = {},
) {
    val app = (LocalContext.current as Activity).app()
    val queued by rememberUpdatedState(onQueued)
    val checked by rememberUpdatedState(onDirectChecked)
    var attempt by remember(request) { mutableIntStateOf(0) }
    val answer = remember(request, attempt) { CompletableDeferred<Unit>() }
    val rejected = remember(request) { request.excludedUrls.toMutableSet() }
    var awaitingConsent by remember(request, attempt) { mutableStateOf(false) }
    var failure by remember(request, attempt) { mutableStateOf<SmartResult.Unavailable?>(null) }
    var stage by remember(request, attempt) { mutableStateOf(SmartStep(SmartPhase.DIRECT)) }
    var automaticRetry by remember(request, attempt) { mutableStateOf(false) }
    val publisher = remember(request.video) { PublisherLinks.episodeOnAj360(request.video) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(request, preferredHeight, attempt) {
        for (pass in 0..1) {
            if (app.downloads.activeSmartJob(request.video) != null) { queued(); return@LaunchedEffect }
            failure = null
            automaticRetry = false
            stage = SmartStep(SmartPhase.DIRECT)
            val result = try {
                app.smartDownloads.find(request.copy(excludedUrls = rejected.toSet()), preferredHeight, permitServers = {
                    if (!app.settings.smartServersAllowed.first()) {
                        if (pass > 0) false else {
                            awaitingConsent = true
                            answer.await()
                            awaitingConsent = false
                            app.settings.setSmartServersAllowed(true)
                            true
                        }
                    } else true
                }, progress = { stage = it }, onDirectChecked = { checked(it) }, onRejectedUrl = { rejected += it })
            } catch (_: IOException) { SmartResult.Unavailable(media = MediaProblem.NETWORK, step = stage) }
            ensureActive()
            when (result) {
                is SmartResult.Ready -> {
                    app.downloads.enqueueSmart(result.candidate, request, preferredHeight.takeIf { it > 0 })
                    queued()
                    return@LaunchedEffect
                }
                is SmartResult.Unavailable -> {
                    failure = result
                    val wait = result.cooldown?.untilMs?.minus(System.currentTimeMillis()) ?: 0L
                    // Retry one fresh lookup while this dialog is open. Don't replay a denied URL or bypass a shared quota.
                    automaticRetry = pass == 0 && result.step?.phase != SmartPhase.DIRECT && wait in 1..MAX_AUTO_RATE_WAIT_MS
                    if (!automaticRetry) break
                    delay(wait)
                }
            }
        }
    }

    val failed = failure
    val seconds = cooldownSeconds(failed?.cooldown?.untilMs)
    val canRetry = failed != null && !failed.protected && failed.resolver != ResolverProblem.ACCESS &&
        failed.media != MediaProblem.SIGN_IN && failed.httpStatus != 401
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("smartDownload.dialog"),
        containerColor = V2.Card, titleContentColor = V2.Text, textContentColor = V2.Text,
        icon = { Icon(Icons.Filled.CloudDownload, null, tint = V2.Mint) },
        title = { Text(if (ar) "تحميل ذكي" else "Smart download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                request.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    awaitingConsent -> SmartServerDisclosure(ar)
                    failed != null -> {
                        Text(failed.text(ar), color = V2.Gold)
                        failed.step?.let { where ->
                            Text(where.phase.label(ar), color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                            where.host?.let { Text(it, color = V2.Sky, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) }
                        }
                        if (seconds > 0) {
                            val time = formatCooldown(seconds, ar)
                            Text(if (automaticRetry) {
                                if (ar) "هنعيد المحاولة مرة واحدة بعد $time لو سيبت النافذة مفتوحة." else "One automatic retry in $time if you keep this dialog open."
                            } else if (ar) "المحاولة متاحة بعد $time." else "You can try again in $time.", color = V2.TextDim,
                                modifier = Modifier.testTag("smartDownload.cooldown"))
                        }
                        if (publisher != null) {
                            TextButton(onClick = { onClose(); runCatching { uriHandler.openUri(publisher) } }) {
                                Text(if (ar) "افتح الحلقة على الجزيرة 360" else "Open the episode on Al Jazeera 360")
                            }
                        }
                        if (!failed.protected && onManual != null && failed.resolver !in setOf(ResolverProblem.ACCESS, ResolverProblem.LIMIT) &&
                            failed.httpStatus !in setOf(401, 429)) {
                            TextButton(onClick = onManual) { Text(if (ar) "تحميل يدوي بخادمك" else "Use your own server") }
                        }
                    }
                    else -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = V2.Mint)
                        Text(stage.text(ar), color = V2.Text)
                    }
                }
            }
        },
        confirmButton = {
            if (awaitingConsent) Button(onClick = { if (!answer.isCompleted) answer.complete(Unit) },
                modifier = Modifier.testTag("smartDownload.enable")) { Text(if (ar) "فعّل وكمّل" else "Enable & continue") }
            else if (canRetry) Button(onClick = { attempt++ }, enabled = seconds == 0L && !automaticRetry,
                modifier = Modifier.testTag("smartDownload.retry")) { Text(if (ar) "حاول تاني" else "Try again") }
            else TextButton(onClick = onClose) { Text(if (failed == null) (if (ar) "إلغاء" else "Cancel") else (if (ar) "رجوع" else "Back")) }
        },
        dismissButton = {
            if (awaitingConsent) TextButton(onClick = onClose) { Text(if (ar) "مش دلوقتي" else "Not now") }
            else if (canRetry) TextButton(onClick = onClose) { Text(if (ar) "إغلاق" else "Close") }
        })
}

@Composable
internal fun SmartServerDisclosure(ar: Boolean) {
    val uriHandler = LocalUriHandler.current
    Text(if (ar) "لو مفيش مصدر مباشر صالح، التطبيق هيجرب خوادم Invidious العامة دي. أول ما يلاقي مصدر قابل للتحميل هيبدأ تلقائيًا."
        else "If no direct source works, Video2 tries these public Invidious servers and starts downloading when a source passes the media check.")
    Text(SmartServers.endpoints.joinToString("\n") { it.host }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = V2.Sky)
    Text(if (ar) "الخادم بيستقبل معرّف فيديو YouTube وبيشوف عنوان اتصالك، مش الكوكيز ولا تسجيل الدخول ولا رابط الوسائط الموقّع. التفعيل مرة واحدة للفيديوهات العامة المسموح لك تحفظها، وتقدر تلغيه من الإعدادات."
        else "The server sees your IP and the YouTube video ID, not your cookies, sign-in data, or signed media link. Enable once for public videos you're allowed to save, and turn it off in Settings anytime.", color = V2.TextDim)
    TextButton(onClick = { runCatching { uriHandler.openUri(SmartServers.privacy) } }) {
        Text(if (ar) "خصوصية الخادم" else "Server privacy policy")
    }
}

private fun SmartStep.text(ar: Boolean): String = when (phase) {
    SmartPhase.DIRECT -> if (ar) "بنتحقق من المصادر المباشرة…" else "Checking direct sources…"
    SmartPhase.LOOKUP -> if (ar) "بنجلب مصدر من $host…" else "Looking up a source on $host…"
    SmartPhase.MEDIA -> if (ar) "بنتأكد من الصورة والصوت قبل التحميل…" else "Checking video and audio before download…"
}

private fun SmartPhase.label(ar: Boolean): String = when (this) {
    SmartPhase.DIRECT -> if (ar) "المرحلة: فحص المصدر المباشر" else "Stage: direct source check"
    SmartPhase.LOOKUP -> if (ar) "المرحلة: جلب رابط التحميل" else "Stage: server lookup"
    SmartPhase.MEDIA -> if (ar) "المرحلة: التحقق من الوسائط" else "Stage: media check"
}

private fun SmartResult.Unavailable.text(ar: Boolean): String {
    val detail = when {
        protected -> if (ar) "المصدر محمي أو مخصص للاشتراك، فالتحميل متوقف." else "This source is protected or restricted to subscribers."
        httpStatus == 429 -> if (ar) "المصدر طلب نوقف الطلبات مؤقتًا." else "The source asked us to pause requests."
        resolver == ResolverProblem.NETWORK -> if (ar) "الاتصال بخادم التحميل ما اكتملش. اتأكد من الإنترنت وحاول تاني." else "Couldn’t reach the download server. Check your connection and try again."
        resolver != null -> resolver.text(ar)
        media != null -> media.text(ar)
        else -> if (ar) "ملقيناش ملف فيديو متاح. افتح صفحة الفيديو أو شغّله وافحص تاني." else "No downloadable video found. Open the video page or play it and scan again."
    }
    return detail + (httpStatus?.let { " (HTTP $it)" } ?: "")
}
