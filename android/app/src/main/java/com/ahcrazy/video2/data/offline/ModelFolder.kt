package com.ahcrazy.video2.data.offline

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Docs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** External storage provider only: no cloud document provider and no app-owned directory. */
internal fun isLocalModelFolder(uri: Uri): Boolean {
    if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents" || !Docs.isTreeUri(uri)) return false
    val id = try { Docs.getTreeDocumentId(uri) } catch (_: IllegalArgumentException) { return false }
    val path = id.substringAfter(':', "").split('/')
    return path.isNotEmpty() && path.none { it.isBlank() || it == "." || it == ".." } && !path.first().equals("Android", ignoreCase = true)
}

/** Keep the native mmap path in private storage; SAF is an explicit user-owned backup, not an inference path. */
internal class ModelFolder(private val resolver: ContentResolver, private val tree: Uri) {
    private val parent = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
    data class Document(val uri: Uri, val name: String, val size: Long?)

    fun documents(): List<Document> {
        val children = Docs.buildChildDocumentsUriUsingTree(tree, Docs.getTreeDocumentId(tree))
        val projection = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_MIME_TYPE)
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(3) != Docs.Document.MIME_TYPE_DIR && !cursor.isNull(1)) {
                        add(Document(Docs.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1),
                            if (cursor.isNull(2)) null else cursor.getLong(2)))
                    }
                }
            }
        } ?: throw IOException("Cannot read the model folder")
    }

    fun candidate(documents: List<Document>, model: OfflineModel): Document? =
        documents.firstOrNull { it.name == model.installedName } ?: documents.firstOrNull { it.name == model.publisherName }

    fun open(doc: Document): InputStream = resolver.openInputStream(doc.uri) ?: throw IOException("Cannot open a model file")

    fun save(documents: List<Document>, model: OfflineModel, source: File, signal: CancellationSignal, progress: (Long) -> Unit) {
        val existing = documents.firstOrNull { it.name == model.installedName }
        if (existing != null) {
            // Never overwrite or delete an existing user file. Verify before reporting a successful backup.
            if (existing.size?.let { it != model.spec.bytes } == true) throw OfflineFailure(OfflineIssue.MODEL_BACKUP)
            try { open(existing).use { copyVerifiedWeight(model.spec, it, null, signal, progress) } }
            catch (error: OfflineFailure) {
                if (error.issue == OfflineIssue.CHECKSUM) throw OfflineFailure(OfflineIssue.MODEL_BACKUP, error)
                throw error
            }
            return
        }
        var pending = Docs.createDocument(resolver, parent, "application/octet-stream", "video2-${UUID.randomUUID()}.part")
            ?: throw IOException("Could not create model backup")
        var published = false
        try {
            source.inputStream().use { input ->
                val fd = resolver.openFileDescriptor(pending, "w", signal) ?: throw IOException("Cannot write model backup")
                ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                    copyVerifiedWeight(model.spec, input, output, signal, progress)
                    output.fd.sync()
                }
            }
            signal.throwIfCanceled()
            pending = Docs.renameDocument(resolver, pending, model.installedName) ?: throw IOException("Cannot publish model backup")
            // The provider may resolve a collision by changing the name. Don't claim that as a valid backup.
            published = resolver.query(pending, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_SIZE), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) == model.installedName && !it.isNull(1) && it.getLong(1) == model.spec.bytes
            } ?: false
            if (!published) throw IOException("Model backup name did not match")
        } finally {
            if (!published) runCatching { Docs.deleteDocument(resolver, pending) }
        }
    }
}

/** Full-size SHA-256 verification for public backups, separate from HTTP. */
internal fun copyVerifiedWeight(
    spec: PinnedModel, input: InputStream, output: OutputStream?, signal: CancellationSignal, progress: (Long) -> Unit
) {
    val sha = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    var bytes = 0L
    var lastUpdate = 0L
    while (true) {
        signal.throwIfCanceled()
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) continue
        bytes += n
        if (bytes > spec.bytes) throw OfflineFailure(OfflineIssue.CHECKSUM)
        sha.update(buffer, 0, n)
        output?.write(buffer, 0, n)
        val now = System.nanoTime()
        if (now - lastUpdate >= 300_000_000L) { lastUpdate = now; progress(bytes) }
    }
    signal.throwIfCanceled()
    if (bytes != spec.bytes || !sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.equals(spec.sha256, ignoreCase = true)) {
        throw OfflineFailure(OfflineIssue.CHECKSUM)
    }
    progress(bytes)
}

internal fun restoreStagingFile(destination: File) = File(destination.parentFile, "${destination.name}.import")

/** Failure/cancellation leaves the previous model and part download untouched. */
internal fun restoreVerifiedWeight(
    spec: PinnedModel, destination: File, signal: CancellationSignal, open: () -> InputStream, progress: (Long) -> Unit
) {
    val staging = restoreStagingFile(destination)
    try {
        open().use { input ->
            FileOutputStream(staging).use { output ->
                copyVerifiedWeight(spec, input, output, signal, progress)
                output.fd.sync()
            }
        }
        signal.throwIfCanceled()
        if (!staging.renameTo(destination)) throw IOException("Cannot install restored model")
    } finally { staging.delete() }
}
