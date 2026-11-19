package com.ahcrazy.video2.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.Video2Application
import com.ahcrazy.video2.core.model.SavedVideo
import com.ahcrazy.video2.data.backup.LibraryArchiveIssue
import com.ahcrazy.video2.data.backup.LibraryTransfer
import com.ahcrazy.video2.data.backup.LibraryTransferResult
import com.ahcrazy.video2.ui.theme.V2
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBackupSheet(app: Video2Application, ar: Boolean, video: SavedVideo?, singleId: String?,
                                canSave: Boolean, onDismiss: () -> Unit) {
    val backup = app.libraryBackup
    val work by app.offlineWork.status.collectAsState()
    val result by backup.result.collectAsState()
    val active = work
    val transfer = LibraryTransfer.entries.firstOrNull { it.kind == active?.kind }
    val enabled = active == null
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { backup.start(LibraryTransfer.SAVE, it, singleId) }
    }
    val verify = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { backup.start(LibraryTransfer.VERIFY, it) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { backup.start(LibraryTransfer.RESTORE, it) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (ar) "نسخ احتياطي واسترجاع" else "Library backup & restore", style = MaterialTheme.typography.titleLarge)
            video?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
            Text(if (ar) "احفظ ملف ZIP في التنزيلات أو المستندات خارج مجلد التطبيق. النسخة فيها الفيديوهات وملفات الـSRT المرفقة؛ الترجمات مش محروقة على الفيديو."
                else "Save the ZIP in Downloads or Documents outside the app. It contains the files and their attached SRTs; captions are not burned in.",
                style = MaterialTheme.typography.bodyMedium)
            Text(if (ar) "تأكد من النسخة قبل حذف التطبيق. النسخة مش مشفّرة، ومش فيها مفاتيح API أو موديلات أو سجل المهام أو الدبلجة."
                else "Verify the backup before uninstalling. This ZIP is not encrypted and doesn't include keys, models, job history, or dubbed audio.",
                color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { save.launch("Kadr-Library-${LocalDate.now()}.zip") }, enabled = enabled && canSave,
                modifier = Modifier.fillMaxWidth().testTag("library.backup.save")) {
                Text(if (singleId == null) { if (ar) "احفظ فيديوهاتي وترجماتها" else "Back up videos & subtitles" }
                    else if (ar) "احفظ الفيديو وترجماته" else "Back up this file & subtitles")
            }
            if (singleId == null) {
                OutlinedButton(onClick = { verify.launch(arrayOf("*/*")) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("library.backup.verify")) {
                    Text(if (ar) "تحقّق من نسخة ZIP" else "Verify a backup ZIP")
                }
                OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("library.backup.restore")) {
                    Text(if (ar) "استرجع الفيديوهات والترجمات" else "Restore videos & subtitles")
                }
                Text(if (ar) "الاسترجاع بيضيف نسخ للمكتبة؛ مش بيمسح ولا بيستبدل الملفات الموجودة. لازم مساحة للنسخة وللفيديوهات بعد الاسترجاع."
                    else "Restore adds copies without replacing existing files. Keep enough space for the ZIP and the restored files.",
                    color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            if (active != null && transfer != null) {
                val label = when (transfer) {
                    LibraryTransfer.SAVE -> if (ar) "جارٍ نسخ المكتبة…" else "Backing up your library…"
                    LibraryTransfer.VERIFY -> if (ar) "جارٍ التحقق من النسخة…" else "Verifying the backup…"
                    LibraryTransfer.RESTORE -> if (ar) "جارٍ استرجاع المكتبة…" else "Restoring your library…"
                }
                Text(if (active.stopping) { if (ar) "جارٍ الإيقاف…" else "Stopping…" } else "$label ${((active.progress) * 100).toInt()}%")
                LinearProgressIndicator(progress = { active.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { app.offlineWork.cancel(transfer.kind, active.key) }, enabled = !active.stopping) {
                    Text(if (ar) "إلغاء" else "Cancel")
                }
            } else if (!enabled) {
                Text(if (ar) "استنى لما المهمة المحلية تخلص أو أوقفها الأول." else "Finish or stop the other on-device task first.", color = V2.Gold)
            }
            if (transfer == null) result?.let {
                val warning = it.issue != null || it.busy || it.report?.excluded?.isNotEmpty() == true
                Text(it.message(ar), color = if (warning) V2.Gold else V2.Text, modifier = Modifier.testTag("library.backup.result"))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun LibraryTransferResult.message(ar: Boolean): String {
    if (busy) return if (ar) "فيه مهمة شغّالة. ما اتعملتش نسخة جديدة." else "Another task is running. No new backup or restore was completed."
    if (cancelled) return if (ar) "العملية اتلغت. المكتبة زي ما هي." else "Cancelled. Your library is unchanged."
    val done = report
    if (done != null) {
        val count = if (ar) "${done.items} ملف، ${done.captioned} منهم بترجمة محفوظة." else "${done.items} files, ${done.captioned} with saved subtitles."
        val text = when (action) {
            LibraryTransfer.SAVE -> if (ar) "اتحفظت النسخة: $count افتح «تحقّق من نسخة ZIP» واختارها قبل حذف أي حاجة."
                else "Backup saved: $count Verify the ZIP before deleting anything."
            LibraryTransfer.VERIFY -> if (ar) "النسخة عدّت التحقق: $count المكتبة ما اتغيّرتش." else "Backup verified: $count Your library was not changed."
            LibraryTransfer.RESTORE -> if (ar) "الاسترجاع اكتمل: $count افتح الفيديو من المكتبة وشغّل الترجمة من أدوات المشغّل."
                else "Restored: $count Open a video from Library and turn on subtitles in the player's tools."
        }
        val skipped = done.excluded.take(3).joinToString("، ") { it.take(80) }
        return if (done.excluded.isEmpty()) text else text + if (ar)
            "\n${done.excluded.size} قائمة بث غير محفوظة محليًا ما دخلتش النسخة: $skipped." else
            "\n${done.excluded.size} non-offline playlists were not included: $skipped."
    }
    return when (issue) {
        LibraryArchiveIssue.EMPTY -> if (ar) "مفيش ملفات متاحة للنسخ." else "There are no files to back up."
        LibraryArchiveIssue.UNAVAILABLE -> if (ar) "في ملف أو ترجمة مفقودة، اتغيّرت، أو مش محفوظة محليًا. جرّب ملف تاني. ما تمسحش الأصل."
            else "A file or subtitle is missing, changed, or not stored offline. Try another file and keep the originals."
        LibraryArchiveIssue.INVALID -> if (ar) "النسخة ناقصة أو تالفة أو مش نسخة كادر مدعومة. ما اتضافتش أي ملفات."
            else "The ZIP is incomplete, damaged, or not a supported Kadr backup. No files were added."
        LibraryArchiveIssue.STORAGE -> if (ar) "المساحة مش كفاية لحفظ النسخة أو استرجاعها. المكتبة زي ما هي."
            else "Not enough storage to finish the transfer. Your library is unchanged."
        else -> if (ar) "العملية ما اكتملتش. راجع مساحة التخزين وصلاحية الملف. ما تمسحش الأصل."
            else "The transfer didn't complete. Check free space and file access, and keep the original files."
    }
}
