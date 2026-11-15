package com.ahcrazy.video2.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.ui.components.ChipPreview
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
    ar: Boolean, onPlay: (SavedVideo) -> Unit, onTranslate: (SavedVideo) -> Unit,
    onReview: (SavedVideo) -> Unit, onDub: (SavedVideo) -> Unit, onConvert: () -> Unit
) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    val videos by app.library.videos.collectAsState()
    val folders by app.library.folders.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SavedVideo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "مستورد"
                        val dest = File(app.library.videosDir, "${System.currentTimeMillis()}-${name.substringAfterLast('/')}")
                        ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
                        val kind = com.ahcrazy.video2.core.model.MediaKind.infer(dest.name, ctx.contentResolver.getType(uri))
                        app.library.add(SavedVideo(title = name, sourceUrl = uri.toString(), localPath = dest.absolutePath,
                            kind = kind, fileSize = dest.length(), extractionMethod = "import"))
                    }
                }
            }
        }
    }

    val filtered = videos.filter {
        (folderFilter == null || it.folderId == folderFilter) &&
            (query.isBlank() || it.title.contains(query, true))
    }
    val continueWatching = videos.filter { it.lastPositionSec > 15 }

    Column(Modifier.fillMaxSize().background(V2.Bg).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ar) "المكتبة" else "Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { importer.launch(arrayOf("video/*", "audio/*")) }) { Icon(Icons.Filled.UploadFile, null, tint = V2.Mint) }
            IconButton(onClick = onConvert) { Icon(Icons.Filled.Transform, null, tint = V2.Gold) }
            IconButton(onClick = { newFolder = true }) { Icon(Icons.Filled.Add, null, tint = Color.White) }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text(L10n.t(if (ar) "ar" else "en", "lib.search"), fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // Folder chips
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FolderChip(if (ar) "الكل" else "All", folderFilter == null) { folderFilter = null }
            folders.forEach { f -> FolderChip("📁 ${f.name}", folderFilter == f.id) { folderFilter = f.id } }
        }

        if (videos.isEmpty()) {
            EmptyState(Icons.Filled.VideoLibrary, L10n.t(if (ar) "ar" else "en", "lib.empty"), L10n.t(if (ar) "ar" else "en", "lib.empty.hint"))
            Button(onClick = { importer.launch(arrayOf("video/*", "audio/*")) }, Modifier.align(Alignment.CenterHorizontally)) {
                Text(L10n.t(if (ar) "ar" else "en", "lib.import"))
            }
        } else {
            if (continueWatching.isNotEmpty() && query.isBlank() && folderFilter == null) {
                Text(L10n.t(if (ar) "ar" else "en", "lib.continue"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                continueWatching.take(3).forEach { v ->
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onPlay(v) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Thumb(v)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("▶ ${if (ar) "استئناف من" else "Resume at"} ${MagicDuration.format(v.lastPositionSec)}", fontSize = 11.sp, color = V2.Gold)
                                LinearProgressIndicator(progress = { v.resumeFraction }, Modifier.fillMaxWidth().padding(top = 6.dp), color = V2.Gold)
                            }
                        }
                    }
                }
            }
            Text(if (query.isBlank()) (if (ar) "كل الفيديوهات" else "All videos") else (if (ar) "نتائج البحث" else "Search results"),
                fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(bottom = 90.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered) { v ->
                    VideoCard(v, ar, onPlay = { onPlay(v) }, onTranslate = { onTranslate(v) }, onReview = { onReview(v) },
                        onDub = { onDub(v) },
                        onRename = { renameTarget = v; renameText = v.title },
                        onDelete = { app.library.delete(v) },
                        onShare = {
                            val f = File(v.localPath)
                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "video/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, v.title))
                        },
                        folders = folders, onMove = { fid -> app.library.setFolder(v, fid) })
                }
            }
        }
    }

    renameTarget?.let { v ->
        AlertDialog(onDismissRequest = { renameTarget = null },
            title = { Text(if (ar) "إعادة تسمية" else "Rename") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = false) },
            confirmButton = { TextButton(onClick = { if (renameText.isNotBlank()) app.library.rename(v, renameText.trim()); renameTarget = null }) { Text(if (ar) "حفظ" else "Save") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
    if (newFolder) {
        AlertDialog(onDismissRequest = { newFolder = false },
            title = { Text(if (ar) "مجلد جديد" else "New folder") },
            text = { OutlinedTextField(newFolderName, { newFolderName = it }, singleLine = true, placeholder = { Text(if (ar) "اسم المجلد" else "Folder name") }) },
            confirmButton = { TextButton(onClick = { if (newFolderName.isNotBlank()) app.library.createFolder(newFolderName.trim()); newFolderName = ""; newFolder = false }) { Text(if (ar) "إنشاء" else "Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}

@Composable private fun FolderChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (active) V2.Accent else V2.Card)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else V2.TextDim)
    }
}

@Composable fun Thumb(v: SavedVideo, size: Int = 54) {
    if (v.thumbnailPath != null) AsyncImage(File(v.thumbnailPath), null,
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(V2.CardSoft))
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(V2.CardSoft), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, null, tint = V2.Accent)
    }
}

@Composable
private fun VideoCard(v: SavedVideo, ar: Boolean, onPlay: () -> Unit, onTranslate: () -> Unit, onReview: () -> Unit,
                      onDub: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit,
                      folders: List<com.ahcrazy.video2.core.model.LibraryFolder>, onMove: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GlassCard {
        Column {
            Box(Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(12.dp)).background(V2.CardSoft).clickable(onClick = onPlay)) {
                if (v.thumbnailPath != null) AsyncImage(File(v.thumbnailPath), null, Modifier.fillMaxSize())
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(MagicDuration.format(v.durationSec), fontSize = 10.sp, color = Color.White)
                }
            }
            Text(v.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
            Text("${v.kind.titleAr} · ${L10n.fmtBytes(v.fileSize)}", fontSize = 10.sp, color = V2.TextDim)
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (v.hasSubtitles) ChipPreview("CC ترجمة", V2.Mint)
                if (v.hasDubbedAudio) ChipPreview("🎙 دبلجة", V2.Gold)
            }
            if (v.resumeFraction > 0f) LinearProgressIndicator(progress = { v.resumeFraction }, Modifier.fillMaxWidth().padding(top = 7.dp), color = V2.Gold)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = V2.TextDim) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (ar) "تشغيل" else "Play") }, onClick = { menu = false; onPlay() })
                    DropdownMenuItem(text = { Text(if (ar) "ترجمة الفيديو" else "Translate video") }, leadingIcon = { Icon(Icons.Filled.ClosedCaption, null) }, onClick = { menu = false; onTranslate() })
                    DropdownMenuItem(text = { Text(if (ar) "مراجعة وتعديل الترجمة" else "Review & edit subtitles") }, onClick = { menu = false; onReview() })
                    DropdownMenuItem(text = { Text(if (v.hasDubbedAudio) "إعادة الدبلجة" else "دبلجة عربية") }, leadingIcon = { Icon(Icons.Filled.Waves, null) }, onClick = { menu = false; onDub() })
                    DropdownMenuItem(text = { Text(if (ar) "مشاركة" else "Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(if (ar) "بدون مجلد" else "No folder") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { menu = false; onMove(null) })
                    folders.forEach { f -> DropdownMenuItem(text = { Text("📁 ${f.name}") }, onClick = { menu = false; onMove(f.id) }) }
                    DropdownMenuItem(text = { Text(if (ar) "إعادة تسمية" else "Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(if (ar) "حذف" else "Delete", color = V2.Accent) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
