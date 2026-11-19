package com.ahcrazy.video2.ui.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.LibraryFolder
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.backup.LibraryTransfer
import com.ahcrazy.video2.ui.components.EmptyState
import com.ahcrazy.video2.ui.player.sharePlayerFile
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

@Composable
fun LibraryScreen(
    ar: Boolean, onPlay: (SavedVideo) -> Unit, onTranslate: (SavedVideo) -> Unit,
    onReview: (SavedVideo) -> Unit, onDub: (SavedVideo) -> Unit, onConvert: () -> Unit
) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    val library = app.library
    val videos by library.videos.collectAsState()
    val folders by library.folders.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val snackbars = remember { SnackbarHostState() }
    val lang = if (ar) "ar" else "en"
    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedVideo?>(null) }
    var deleting by remember { mutableStateOf<SavedVideo?>(null) }
    var titleInput by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var archiveSheet by rememberSaveable { mutableStateOf(false) }
    var archiveVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    val archiveResult by app.libraryBackup.result.collectAsState()
    LaunchedEffect(archiveResult) {
        if (archiveResult?.let { it.action == LibraryTransfer.RESTORE && it.report != null } == true) {
            query = ""
            folderFilter = null
            audioOnly = false
        }
    }

    fun warn(message: String) { scope.launch { snackbars.showSnackbar(message) } }
    fun editLibrary(action: () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { snackbars.showSnackbar(if (ar) "ما قدرناش نحفظ التغيير" else "Couldn't save the change") }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() || importing) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val added = try {
                withContext(Dispatchers.IO) {
                    uris.count { uri ->
                        try {
                            importToLibrary(library, ctx.contentResolver, uri, if (ar) "ملف مستورد" else "Imported file")
                            true
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { false }
                    }
                }
            } finally { importing = false }
            if (added > 0) { folderFilter = null; audioOnly = false; query = "" }
            if (added < uris.size) snackbars.showSnackbar(if (ar) "بعض الملفات ما اتضافتش. راجع المساحة وصلاحية الملف." else
                "Some files couldn't be imported. Check free space and file access.")
        }
    }
    val importFiles: () -> Unit = { focus.clearFocus(); importer.launch(arrayOf("video/*", "audio/*")) }
    val term = query.trim()
    val filtered = remember(videos, folderFilter, audioOnly, term) {
        videos.filter { (folderFilter == null || it.folderId == folderFilter) &&
            (!audioOnly || it.kind.isAudioFile) && (term.isEmpty() || it.title.contains(term, ignoreCase = true)) }
    }
    val featured = remember(videos, folderFilter, audioOnly, term) {
        if (term.isEmpty() && folderFilter == null && !audioOnly) libraryResumeCandidate(videos) else null
    }
    val backdrop = Brush.verticalGradient(0f to lerp(V2.Bg, V2.Card, .8f), .42f to V2.Bg, 1f to V2.Bg)

    Box(Modifier.fillMaxSize().background(backdrop)) {
        LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 740.dp).fillMaxSize().testTag("library.feed"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp)) {
            item("heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ar) "المكتبة" else "Library", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, if (ar) "خيارات المكتبة" else "Library options", tint = V2.TextDim)
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (ar) "نسخ احتياطي واسترجاع" else "Backup & restore") },
                                modifier = Modifier.testTag("library.backup"),
                                onClick = { menu = false; archiveVideoId = null; archiveSheet = true })
                            DropdownMenuItem(text = { Text(if (ar) "مجلد جديد" else "New folder") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                onClick = { menu = false; titleInput = ""; newFolder = true })
                            DropdownMenuItem(text = { Text(if (ar) "تحويل ملف" else "Convert a file") },
                                leadingIcon = { Icon(Icons.Outlined.Transform, null) }, onClick = { menu = false; onConvert() })
                        }
                    }
                    FilledTonalIconButton(onClick = importFiles, enabled = !importing,
                        modifier = Modifier.size(48.dp).testTag("library.import"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = V2.CardSoft, contentColor = V2.Accent)) {
                        if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.UploadFile, L10n.t(lang, "lib.import"))
                    }
                }
            }
            item("search") {
                OutlinedTextField(query, { query = it },
                    Modifier.fillMaxWidth().padding(top = 14.dp).testTag("library.search"),
                    placeholder = { Text(L10n.t(lang, "lib.search"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = V2.TextDim) },
                    trailingIcon = if (query.isNotEmpty()) {{
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, if (ar) "امسح البحث" else "Clear search") }
                    }} else null,
                    singleLine = true, shape = RoundedCornerShape(28.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = V2.Card, unfocusedContainerColor = V2.Card.copy(alpha = .64f),
                        focusedBorderColor = V2.Accent, unfocusedBorderColor = V2.Divider),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            }
            item("filters") {
                LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).testTag("library.folders"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("all") { LibraryChip(if (ar) "الكل" else "All", folderFilter == null && !audioOnly) {
                        folderFilter = null; audioOnly = false
                    } }
                    if (audioOnly || videos.any { it.kind.isAudioFile }) item("audio") {
                        LibraryChip(if (ar) "صوت" else "Audio", audioOnly) { folderFilter = null; audioOnly = true }
                    }
                    items(folders, key = { "folder:${it.id}" }) { folder ->
                        LibraryChip(folder.name, folderFilter == folder.id) { folderFilter = folder.id; audioOnly = false }
                    }
                }
            }
            if (featured != null) item("resume") {
                Text(L10n.t(lang, "lib.continue"), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                ResumeCover(featured, ar) { focus.clearFocus(); onPlay(featured) }
            }
            if (videos.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Outlined.VideoLibrary, L10n.t(lang, "lib.empty"), L10n.t(lang, "lib.empty.hint"))
                    Button(onClick = importFiles, enabled = !importing) { Text(L10n.t(lang, "lib.import")) }
                }
            } else {
                item("shelf-title") {
                    Text(when {
                        term.isNotEmpty() -> if (ar) "نتائج البحث" else "Search results"
                        audioOnly -> if (ar) "ملفات الصوت" else "Audio files"
                        else -> if (ar) "فيديوهاتي" else "My videos"
                    }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = if (featured != null) 23.dp else 4.dp, bottom = 5.dp))
                }
                if (filtered.isEmpty()) item("no-match") {
                    EmptyState(Icons.Outlined.Search, if (ar) "مفيش ملفات هنا" else "No matching files",
                        if (ar) "جرّب كلمة تانية أو اختار «الكل»." else "Try another search or select All.")
                }
                items(filtered, key = { "video:${it.id}" }) { video ->
                    LibraryVideoRow(video, ar, folders, onPlay = { focus.clearFocus(); onPlay(video) },
                        onTranslate = { onTranslate(video) }, onReview = { onReview(video) }, onDub = { onDub(video) },
                        onRename = { editing = video; titleInput = video.title }, onDelete = { deleting = video },
                        onBackup = { archiveVideoId = video.id; archiveSheet = true },
                        onShare = {
                            if (!sharePlayerFile(ctx, video)) warn(if (ar) "المشاركة مش متاحة للملف ده" else "Couldn't share this file")
                        }, onMove = { id -> editLibrary { library.setFolder(video, id) } })
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }

    if (archiveSheet) {
        val selected = videos.firstOrNull { it.id == archiveVideoId }
        LibraryBackupSheet(app, ar, selected, archiveVideoId, canSave = !importing &&
            (if (archiveVideoId != null) selected != null else videos.isNotEmpty())) { archiveSheet = false }
    }
    if (editing != null || newFolder) {
        val target = editing
        fun dismiss() { editing = null; newFolder = false }
        AlertDialog(onDismissRequest = ::dismiss,
            title = { Text(if (target != null) L10n.t(lang, "lib.rename") else if (ar) "مجلد جديد" else "New folder") },
            text = { OutlinedTextField(titleInput, { titleInput = it }, singleLine = true,
                label = { Text(if (ar) "الاسم" else "Name") }) },
            confirmButton = { TextButton(enabled = titleInput.isNotBlank(), onClick = {
                val name = titleInput.trim()
                dismiss()
                editLibrary { if (target != null) library.rename(target, name) else library.createFolder(name) }
            }) { Text(if (ar) "حفظ" else "Save") } },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
    deleting?.let { video ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (ar) "تمسح الملف؟" else "Delete this file?") },
            text = { Text(if (ar) "«${video.title}» وأي ترجمة ودبلجة محفوظة معاه هيتحذفوا من المكتبة." else
                "“${video.title}” and its saved captions and dubbed audio will be removed from the library.") },
            confirmButton = { TextButton(onClick = { deleting = null; editLibrary { library.delete(video) } }) {
                Text(L10n.t(lang, "lib.delete"), color = V2.Danger)
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(if (ar) "إلغاء" else "Cancel") } })
    }
}

@Composable
private fun LibraryChip(label: String, active: Boolean, onClick: () -> Unit) {
    FilterChip(selected = active, onClick = onClick, shape = CircleShape,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.widthIn(max = 190.dp).heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(containerColor = V2.Card.copy(alpha = .62f), labelColor = V2.TextDim,
            selectedContainerColor = V2.Accent.copy(alpha = .19f), selectedLabelColor = V2.Accent))
}

@Composable
private fun ResumeCover(video: SavedVideo, ar: Boolean, onPlay: () -> Unit) {
    val art by libraryArtwork(video)
    val fraction = (video.durationSec ?: art.duration)?.let { duration ->
        if (duration.isFinite() && duration > 0) (video.lastPositionSec / duration).toFloat().coerceIn(0f, 1f) else null
    }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(16f / 9f).clip(shape)
        .testTag("library.resume").clickable(role = Role.Button,
            onClickLabel = if (ar) "كمّل المشاهدة" else "Resume playback", onClick = onPlay)) {
        LibraryCover(video, art, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent,
            .32f to Color.Transparent, 1f to Color(0xF0050A15))))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(video.title, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(MagicDuration.format(video.lastPositionSec), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = V2.TextDim, maxLines = 1)
                if (fraction != null) LinearProgressIndicator(progress = { fraction },
                    Modifier.widthIn(max = 240.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = V2.Accent, trackColor = Color.White.copy(alpha = .22f))
            }
            Surface(Modifier.size(48.dp), shape = CircleShape, color = Color(0xA3293A59), border = BorderStroke(1.dp, Color(0x45FFFFFF))) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White) }
            }
        }
    }
}

@Composable
private fun LibraryVideoRow(video: SavedVideo, ar: Boolean, folders: List<LibraryFolder>, onPlay: () -> Unit,
                            onTranslate: () -> Unit, onReview: () -> Unit, onDub: () -> Unit,
                            onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit, onBackup: () -> Unit,
                            onMove: (String?) -> Unit) {
    val art by libraryArtwork(video)
    val duration = video.durationSec?.takeIf { it.isFinite() && it > 0 } ?: art.duration
    val lang = if (ar) "ar" else "en"
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).testTag("library.video.${video.id}"),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).clickable(role = Role.Button,
                onClickLabel = L10n.t(lang, "lib.play"), onClick = onPlay).padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryCover(video, art, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val detail = duration?.let { MagicDuration.format(it) } ?: if (ar) video.kind.titleAr else video.kind.titleEn
                        Text(detail, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = V2.TextDim, modifier = Modifier.weight(1f, fill = false))
                        if (video.subtitleFiles.isNotEmpty()) Icon(Icons.Outlined.ClosedCaption,
                            if (ar) "مع ترجمة" else "Has captions", Modifier.size(16.dp), tint = V2.Accent)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, if (ar) "خيارات الملف" else "File options", tint = V2.TextDim) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(L10n.t(lang, "lib.play")) }, onClick = { menu = false; onPlay() })
                    DropdownMenuItem(text = { Text(if (ar) "ترجمة الفيديو" else "Translate video") },
                        leadingIcon = { Icon(Icons.Outlined.ClosedCaption, null) }, onClick = { menu = false; onTranslate() })
                    DropdownMenuItem(text = { Text(if (ar) "مراجعة وتعديل الترجمة" else "Review & edit subtitles") }, onClick = { menu = false; onReview() })
                    DropdownMenuItem(text = { Text(if (ar) { if (video.hasDubbedAudio) "إعادة الدبلجة" else "دبلجة" }
                        else if (video.hasDubbedAudio) "Dub again" else "Arabic dub") },
                        leadingIcon = { Icon(Icons.Outlined.Waves, null) }, onClick = { menu = false; onDub() })
                    DropdownMenuItem(text = { Text(if (ar) "مشاركة" else "Share") }, enabled = video.kind != MediaKind.HLS,
                        onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(if (ar) "نسخة بالفيديو والترجمة" else "Back up with subtitles") },
                        onClick = { menu = false; onBackup() })
                    if (folders.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(if (ar) "بدون مجلد" else "No folder") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) }, onClick = { menu = false; onMove(null) })
                        folders.forEach { folder -> DropdownMenuItem(text = { Text(folder.name) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) }, onClick = { menu = false; onMove(folder.id) }) }
                    }
                    DropdownMenuItem(text = { Text(L10n.t(lang, "lib.rename")) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(L10n.t(lang, "lib.delete"), color = V2.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        HorizontalDivider(color = V2.Divider)
    }
}

private fun importToLibrary(library: LibraryStore, resolver: ContentResolver, uri: Uri, fallback: String) {
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst()) row.getString(0) else null
        }
    }.getOrNull()
    val name = (displayName ?: uri.lastPathSegment?.substringAfterLast('/')).orEmpty().trim().take(240).ifBlank { fallback }
    val id = UUID.randomUUID().toString()
    val kind = MediaKind.infer(name, resolver.getType(uri))
    val dest = File(library.videosDir, "$id.${kind.ext}")
    val temporary = File(library.videosDir, "$id.importing")
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't open selected file")
        input.use { source -> temporary.outputStream().buffered().use { output -> source.copyTo(output) } }
        if (temporary.length() <= 0 || !temporary.renameTo(dest)) throw IOException("Can't save selected file")
        library.add(SavedVideo(id = id, title = name, sourceUrl = uri.toString(), localPath = dest.absolutePath,
            kind = kind, fileSize = dest.length(), extractionMethod = "import"))
    } catch (e: Exception) {
        dest.delete()
        throw e
    } finally { temporary.delete() }
}
