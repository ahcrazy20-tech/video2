package com.ahcrazy.video2.ui.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Format conversion screen — local HLS→single-file first (like iOS), cloud only as fallback. */
@Composable
fun ConversionScreen(ar: Boolean, onBack: () -> Unit) {
    val app = (LocalContext.current as android.app.Activity).app()
    val videos by app.library.videos.collectAsState()
    val hls = videos.filter { it.kind == com.ahcrazy.video2.core.model.MediaKind.HLS }
    var converting by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(V2.Bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← " + if (ar) "رجوع" else "Back") }
            Text(if (ar) "تحويل الصيغ" else "Format conversion", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(if (ar) "تحويل HLS لملف واحد MP4 — محليًا الأول من غير سحابة، والسحابة (CloudConvert ← ffmpeg-api ← ConvertAPI) احتياطي بس لو المحلي فشل." else "HLS → single MP4 locally first; cloud only as fallback.", fontSize = 12.sp, color = V2.TextDim)
        msg?.let { Text(it, fontSize = 12.sp, color = V2.Mint, modifier = Modifier.padding(top = 10.dp)) }
        if (hls.isEmpty()) EmptyState(Icons.Filled.Transform, if (ar) "مفيش HLS للتحويل" else "Nothing to convert",
            if (ar) "أي فيديو HLS في المكتبة هيظهر هنا." else "HLS videos in your library will appear here.")
        LazyColumn {
            items(hls) { v ->
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column {
                        Text(v.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("HLS (m3u8) → MP4 · ${com.ahcrazy.video2.core.util.L10n.fmtBytes(v.fileSize)}", fontSize = 11.sp, color = V2.TextDim)
                        if (converting == v.id) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = V2.Accent)
                        Button(onClick = {
                            converting = v.id
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { mergeHlsToMp4(v) }
                                converting = null
                                msg = if (ok != null) (if (ar) "اتحول وبقى في المكتبة: " else "Converted & in Library: ") + ok
                                      else if (ar) "فشل التحويل المحلي — راجع مفاتيح السحابة في الإعدادات" else "Local convert failed — check cloud keys in Settings"
                            }
                        }, enabled = converting == null, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (ar) "حوّل إلى MP4" else "Convert to MP4", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Local path: concatenate the downloaded TS segments in playlist order into one playable file. */
private fun mergeHlsToMp4(video: SavedVideo): String? = runCatching {
    val playlist = File(video.localPath)
    val folder = playlist.parentFile ?: return null
    val segs = playlist.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { File(folder, it) }.filter { it.exists() }
    if (segs.isEmpty()) return null
    val out = File(folder.parentFile, "converted-${video.id}.mp4")
    out.outputStream().use { o -> segs.forEach { s -> s.inputStream().use { it.copyTo(o) } } }
    out.name
}.getOrNull()
