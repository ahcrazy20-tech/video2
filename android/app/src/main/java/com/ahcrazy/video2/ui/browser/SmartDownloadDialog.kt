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
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaProblem
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.SmartDownloadRequest
import com.ahcrazy.video2.data.SmartResult
import com.ahcrazy.video2.data.SmartServers
import com.ahcrazy.video2.data.SmartStep
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CompletableDeferred
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
    val answer = remember(request) { CompletableDeferred<Unit>() }
    var awaitingConsent by remember(request) { mutableStateOf(false) }
    var failure by remember(request) { mutableStateOf<SmartResult.Unavailable?>(null) }
    var stage by remember(request) { mutableStateOf<SmartStep>(SmartStep.Direct) }

    LaunchedEffect(request) {
        if (app.downloads.activeSmartJob(request.video) != null) { queued(); return@LaunchedEffect }
        val result = try {
            app.smartDownloads.find(request, preferredHeight, permitServers = {
                if (!app.settings.smartServersAllowed.first()) {
                    awaitingConsent = true
                    answer.await()
                    awaitingConsent = false
                    app.settings.setSmartServersAllowed(true)
                }
                true
            }, progress = { stage = it }, onDirectChecked = { checked(it) })
        } catch (_: IOException) { SmartResult.Unavailable(media = MediaProblem.NETWORK) }
        ensureActive()
        when (result) {
            is SmartResult.Ready -> {
                app.downloads.enqueueSmart(result.candidate, request, preferredHeight.takeIf { it > 0 })
                queued()
            }
            is SmartResult.Unavailable -> failure = result
        }
    }

    val failed = failure
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
            else TextButton(onClick = onClose) { Text(if (failed == null) (if (ar) "إلغاء" else "Cancel") else (if (ar) "رجوع" else "Back")) }
        },
        dismissButton = {
            if (awaitingConsent) TextButton(onClick = onClose) { Text(if (ar) "مش دلوقتي" else "Not now") }
        })
}

@Composable
internal fun SmartServerDisclosure(ar: Boolean) {
    val uriHandler = LocalUriHandler.current
    Text(if (ar) "لو مفيش مصدر مباشر صالح، التطبيق هيجرب خوادم Invidious العامة دي. أول ما يلاقي مصدر قابل للتحميل هيبدأ تلقائيًا."
        else "If no direct source works, Video2 tries these public Invidious servers and starts downloading when a source passes the media check.")
    Text(SmartServers.endpoints.joinToString("\n") { it.host }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = V2.Sky)
    Text(if (ar) "الخادم بيستقبل معرّف فيديو YouTube، مش الكوكيز ولا تسجيل الدخول ولا رابط الفيديو الموقّع. فعّلها للفيديوهات العامة المسموح لك تحفظها. التفعيل مرة واحدة وتقدر تلغيه من الإعدادات."
        else "Only the YouTube video ID is sent, not your cookies, sign-in data, or signed media link. Use this for public videos you're allowed to save. Enable it once and turn it off in Settings anytime.", color = V2.TextDim)
    TextButton(onClick = { runCatching { uriHandler.openUri(SmartServers.privacy) } }) {
        Text(if (ar) "خصوصية الخادم" else "Server privacy policy")
    }
}

private fun SmartStep.text(ar: Boolean): String = when (this) {
    SmartStep.Direct -> if (ar) "بنتحقق من المصادر المباشرة…" else "Checking direct sources…"
    SmartStep.Measuring -> if (ar) "بنرتّب الخوادم حسب سرعة الاستجابة…" else "Ranking servers by response time…"
    is SmartStep.Resolving -> if (ar) "بنجلب مصدر من $host…" else "Looking up a source on $host…"
    is SmartStep.Checking -> if (ar) "بنتحقق من الوسائط قبل التحميل…" else "Verifying media before download…"
}

private fun SmartResult.Unavailable.text(ar: Boolean): String {
    val detail = when {
        protected -> if (ar) "المصدر محمي أو مخصص للاشتراك، فالتحميل متوقف." else "This source is protected or restricted to subscribers."
        httpStatus == 429 -> ResolverProblem.LIMIT.text(ar)
        resolver != null -> resolver.text(ar)
        media != null -> media.text(ar)
        else -> if (ar) "ملقيناش ملف فيديو متاح. افتح صفحة الفيديو أو شغّله وافحص تاني." else "No downloadable video found. Open the video page or play it and scan again."
    }
    return detail + (httpStatus?.let { " (HTTP $it)" } ?: "")
}
