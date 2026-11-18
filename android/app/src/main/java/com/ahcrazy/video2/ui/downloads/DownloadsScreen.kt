package com.ahcrazy.video2.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.DownloadJob
import com.ahcrazy.video2.core.model.DownloadState
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2

@Composable
fun DownloadsScreen(ar: Boolean) {
    val app = (LocalContext.current as android.app.Activity).app()
    val jobs by app.downloads.jobs.collectAsState()
    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Text(if (ar) "التحميلات" else "Downloads", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        if (jobs.isEmpty()) EmptyState(Icons.Filled.CloudDownload, L10n.t(if (ar) "ar" else "en", "dl.empty"),
            if (ar) "التحميلات بتكمل في الخلفية. لو النظام وقف التطبيق، تقدر تستأنف من هنا." else "Downloads continue in the background. If Android stops the app, reopen it and tap Resume.")
        LazyColumn {
            items(jobs) { job -> JobRow(job, ar,
                onPause = { app.downloads.cancel(job.id) },
                onResume = { app.downloads.resume(job.id) },
                onRemove = { app.downloads.remove(job.id) }) }
        }
    }
}

@Composable
private fun JobRow(job: DownloadJob, ar: Boolean, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column {
            Text(job.media.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stateText(job.state, ar), fontSize = 11.sp, color = stateColor(job.state), fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (job.state.isBusy) Text("${(job.progress * 100).toInt()}% · ${L10n.fmtBytes(job.bytesWritten)}", fontSize = 11.sp, color = V2.TextDim)
            }
            if (job.state.isBusy || job.state == DownloadState.PAUSED) {
                LinearProgressIndicator(progress = { job.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 7.dp),
                    color = if (job.state == DownloadState.PAUSED) V2.Gold else V2.Accent)
            }
            job.errorMessage?.let { Text(it, fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp), maxLines = 2) }
            Text(job.media.extractionMethod, fontSize = 10.sp, color = V2.TextDim, modifier = Modifier.padding(top = 4.dp))
            Row {
                when (job.state) {
                    DownloadState.RUNNING, DownloadState.QUEUED ->
                        androidx.compose.material3.TextButton(onClick = onPause) { Text(if (ar) "إيقاف مؤقت" else "Pause") }
                    DownloadState.PAUSED, DownloadState.FAILED ->
                        androidx.compose.material3.TextButton(onClick = onResume) { Text(if (ar) "استئناف" else "Resume") }
                    else -> {}
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onRemove) { Text(if (ar) "حذف" else "Remove", color = V2.Accent) }
            }
        }
    }
}

private fun stateText(s: DownloadState, ar: Boolean) = when (s) {
    DownloadState.QUEUED -> if (ar) "في الطابور…" else "Queued…"
    DownloadState.RUNNING -> if (ar) "جارٍ التحميل…" else "Downloading…"
    DownloadState.PAUSED -> if (ar) "متوقف مؤقتًا" else "Paused"
    DownloadState.FAILED -> if (ar) "فشل" else "Failed"
    DownloadState.COMPLETED -> if (ar) "اكتمل ✓ — في المكتبة" else "Done ✓ — in Library"
    DownloadState.BLOCKED_DRM -> if (ar) "محظور (DRM)" else "Blocked (DRM)"
}
private fun stateColor(s: DownloadState) = when (s) {
    DownloadState.COMPLETED -> V2.Mint
    DownloadState.FAILED, DownloadState.BLOCKED_DRM -> V2.Accent
    DownloadState.PAUSED -> V2.Gold
    else -> V2.TextDim
}
