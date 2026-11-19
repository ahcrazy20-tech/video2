package com.ahcrazy.video2.data.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.ahcrazy.video2.data.LibraryStore
import com.ahcrazy.video2.data.offline.OfflineWork
import com.ahcrazy.video2.data.offline.OfflineWorkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class LibraryTransfer(val kind: OfflineWorkKind) {
    SAVE(OfflineWorkKind.LIBRARY_SAVE), VERIFY(OfflineWorkKind.LIBRARY_VERIFY), RESTORE(OfflineWorkKind.LIBRARY_RESTORE)
}
internal data class LibraryTransferResult(val action: LibraryTransfer, val report: LibraryArchiveReport? = null,
                                          val issue: LibraryArchiveIssue? = null, val cancelled: Boolean = false, val busy: Boolean = false)

/** Files are owned by the user through Android's document picker, not under Android/data. */
internal class LibraryBackup(context: Context, library: LibraryStore, private val work: OfflineWork) {
    private val resolver = context.contentResolver
    private val codec = LibraryArchive(library, File(context.filesDir, "LibraryRestoreStaging"))
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _result = MutableStateFlow<LibraryTransferResult?>(null)
    val result = _result.asStateFlow()

    fun start(action: LibraryTransfer, uri: Uri, videoId: String? = null) {
        _result.value = null
        fun discardIfSaving() {
            if (action == LibraryTransfer.SAVE) cleanupScope.launch {
                // This URI is only ever returned by ACTION_CREATE_DOCUMENT; never delete an input archive.
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
        var report: LibraryArchiveReport? = null
        try {
            val started = work.submit(action.kind, UUID.randomUUID().toString(), onFinish = { error ->
                if (error != null) {
                    discardIfSaving()
                    _result.value = when {
                        OfflineWork.cancelled(error) -> LibraryTransferResult(action, cancelled = true)
                        else -> LibraryTransferResult(action, issue = (error as? LibraryArchiveException)?.issue ?: LibraryArchiveIssue.IO)
                    }
                } else _result.value = LibraryTransferResult(action, report = report)
            }) { signal, progress ->
                val output = action == LibraryTransfer.SAVE
                val fd = resolver.openFileDescriptor(uri, if (output) "w" else "r", signal)
                    ?: throw IOException("Cannot open the selected document")
                report = if (output) ParcelFileDescriptor.AutoCloseOutputStream(fd).use {
                    codec.save(it, videoId, signal, progress)
                } else ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                    if (action == LibraryTransfer.RESTORE) codec.restore(it, signal, progress) else codec.verify(it, signal, progress)
                }
                progress(1.0)
            }
            if (!started) {
                discardIfSaving()
                _result.value = LibraryTransferResult(action, busy = true)
            }
        } catch (_: Exception) {
            discardIfSaving()
            _result.value = LibraryTransferResult(action, issue = LibraryArchiveIssue.IO)
        }
    }
}
