package com.ahcrazy.video2.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.DownloadCooldown
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.model.DownloadJob
import com.ahcrazy.video2.core.model.DownloadState
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.data.PublisherLinks
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.cooldownSeconds
import com.ahcrazy.video2.ui.components.formatCooldown
import com.ahcrazy.video2.ui.theme.V2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(ar: Boolean, onOpenSource: (DownloadJob) -> Unit, onOpenLibrary: () -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    val jobs by app.downloads.jobs.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(V2.Bg), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (ar) "التحميلات" else "Downloads", style = MaterialTheme.typography.headlineMedium, color = V2.Text)
            Text(if (ar) "تابع التقدم وكمل التحميلات المتوقفة من هنا." else "Track progress and resume paused downloads here.",
                color = V2.TextDim)
        }
        if (jobs.isEmpty()) item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CloudDownload, null, tint = V2.Mint)
                    Text(if (ar) "لسه مفيش تحميلات" else "No downloads yet", style = MaterialTheme.typography.titleMedium)
                    Text(if (ar) "اختار مصدر متاح من «الالتقاط» أو استخدم رابط تحميل مباشر." else "Choose a source in Capture, or use a direct download link.", color = V2.TextDim)
                }
            }
        } else item {
            val active = jobs.count { it.state.isBusy }
            val completed = jobs.count { it.state == DownloadState.COMPLETED }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, enabled = false, label = { Text(if (ar) "$active قيد التحميل" else "$active in progress") },
                    colors = AssistChipDefaults.assistChipColors(disabledLabelColor = V2.Sky))
                AssistChip(onClick = onOpenLibrary, label = { Text(if (ar) "$completed في المكتبة" else "$completed in Library") })
            }
        }
        items(jobs, key = { it.id }) { job ->
            val page = httpMediaUrl(job.media.pageUrl ?: job.media.publicPageUrl ?: job.media.requestReferer)?.toString()
            JobRow(job, ar, cooldown = app.downloads.retryCooldown(job),
                onPause = { app.downloads.cancel(job.id) }, onResume = { app.downloads.resume(job.id) },
                onRemove = { app.downloads.remove(job.id) }, onOpenSource = page?.let { { onOpenSource(job) } },
                onOpenLibrary = onOpenLibrary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobRow(job: DownloadJob, ar: Boolean, cooldown: DownloadCooldown?, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit,
                   onOpenSource: (() -> Unit)?, onOpenLibrary: () -> Unit) {
    val resolver = ResolverType.forMedia(job.media)
    val video = YouTubeVideo.fromMedia(job.media)
    val publisher = PublisherLinks.episodeOnAj360(video)
    val handler = LocalUriHandler.current
    val seconds = cooldownSeconds(cooldown?.untilMs)
    val freshSource = video != null && job.httpStatus !in setOf(401, 429) &&
        (resolver != null || job.problem?.needsNewLink == true)
    val (icon, tint) = when (job.state) {
        DownloadState.COMPLETED -> Icons.Filled.CheckCircle to V2.Mint
        DownloadState.PAUSED -> Icons.Filled.PauseCircle to V2.Gold
        DownloadState.FAILED, DownloadState.BLOCKED_DRM -> Icons.Filled.ErrorOutline to V2.Accent
        else -> Icons.Filled.CloudDownload to V2.Sky
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .12f)) {
                    Icon(icon, null, Modifier.padding(9.dp).size(22.dp), tint = tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(job.media.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stateText(job, ar), style = MaterialTheme.typography.bodySmall, color = tint)
                    resolver?.let { Text((if (ar) "الخادم: " else "Server: ") + it.label, style = MaterialTheme.typography.bodySmall, color = V2.TextDim) }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, if (ar) "إزالة من القائمة" else "Remove from queue", tint = V2.TextDim) }
            }
            if (job.state == DownloadState.RUNNING || job.state == DownloadState.PAUSED || job.state == DownloadState.COMPLETED) {
                val showPercent = job.progress > 0 || job.state == DownloadState.COMPLETED
                val bytes = L10n.fmtBytes(job.bytesWritten)
                Text(if (showPercent) "${(job.progress.coerceIn(0.0, 1.0) * 100).toInt()}% · $bytes" else bytes,
                    style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
                if (job.state == DownloadState.RUNNING && !showPercent) LinearProgressIndicator(Modifier.fillMaxWidth(), color = tint)
                else if (job.state != DownloadState.COMPLETED) LinearProgressIndicator(progress = { job.progress.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = tint)
            }
            val error = if (job.httpStatus == 429) {
                if (ar) "المصدر وقف نقل الملف مؤقتًا (HTTP 429)." else "The source rate-limited the transfer (HTTP 429)."
            } else if (job.problem?.needsNewLink == true) {
                val status = job.httpStatus?.let { " (HTTP $it)" }.orEmpty()
                job.problem.text(ar) + status
            } else if (ar) job.errorMessage ?: job.problem?.ar else job.problem?.en ?: when (job.state) {
                DownloadState.BLOCKED_DRM -> "This source is protected and can’t be downloaded."
                DownloadState.FAILED -> "The download didn’t finish. Check the source or try again."
                else -> null
            }
            error?.let { Text(it, color = V2.Gold, style = MaterialTheme.typography.bodySmall) }
            if (job.httpStatus == 429) job.rateLimitHost?.let {
                Text(it, color = V2.Sky, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            }
            if (job.canRetry && seconds > 0) {
                val time = formatCooldown(seconds, ar)
                Text(if (ar) "ممكن تستأنف بعد $time." else "You can resume in $time.",
                    color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (job.state.isBusy) TextButton(onClick = onPause) { Text(if (ar) "إيقاف مؤقت" else "Pause") }
                if (job.canRetry) TextButton(onClick = onResume, enabled = seconds == 0L) {
                    Text(if (job.state == DownloadState.FAILED) (if (ar) "حاول تاني" else "Try again") else (if (ar) "استئناف" else "Resume"))
                }
                if (onOpenSource != null && job.state == DownloadState.FAILED && job.httpStatus != 429) TextButton(onClick = onOpenSource) {
                    Text(when {
                        freshSource -> if (ar) "ابحث عن مصدر جديد" else "Find a fresh source"
                        job.problem?.needsNewLink == true -> if (ar) "ارجع للمصدر وافحص البدائل" else "Return to source and check alternatives"
                        else -> if (ar) "افتح صفحة المصدر" else "Open source page"
                    })
                }
                if (publisher != null && job.state == DownloadState.FAILED) TextButton(onClick = { runCatching { handler.openUri(publisher) } }) {
                    Text(if (ar) "افتح الحلقة على الجزيرة 360" else "Open on Al Jazeera 360")
                }
                if (job.state == DownloadState.COMPLETED) TextButton(onClick = onOpenLibrary) { Text(if (ar) "افتح المكتبة" else "Open Library") }
            }
        }
    }
}

private fun stateText(job: DownloadJob, ar: Boolean) = when (job.state) {
    DownloadState.QUEUED -> if (ar) "في الانتظار" else "Queued"
    DownloadState.RUNNING -> if (ar) "جارٍ التحميل" else "Downloading"
    DownloadState.PAUSED -> if (ar) "متوقف مؤقتًا" else "Paused"
    DownloadState.FAILED -> if (job.problem?.needsNewLink == true) (if (ar) "محتاج رابط جديد" else "Needs a new link")
        else (if (ar) "التحميل ما اكتملش" else "Download incomplete")
    DownloadState.COMPLETED -> if (ar) "اتحفظ في المكتبة" else "Saved to Library"
    DownloadState.BLOCKED_DRM -> if (ar) "مصدر محمي بـ DRM" else "DRM protected"
}
