package io.github.rufid.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.rufid.archive.ArchiveEntryInfo
import io.github.rufid.archive.ArchiveEntrySink
import java.io.OutputStream

class SafTreeArchiveSink(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : ArchiveEntrySink {
    private val directoryCache = mutableMapOf<String, Uri>()
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    init {
        directoryCache[""] = rootUri
    }

    override fun createDirectory(entry: ArchiveEntryInfo) {
        ensureDirectory(entry.path.safeSegments())
    }

    override fun openEntry(entry: ArchiveEntryInfo): OutputStream {
        val segments = entry.path.safeSegments()
        require(segments.isNotEmpty()) { "Archive file path is empty" }
        val parent = ensureDirectory(segments.dropLast(1))
        val name = segments.last()
        val file = findChild(parent, name, directory = false)
            ?: DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name)
            ?: error("Unable to create file ${entry.path}")
        return requireNotNull(contentResolver.openOutputStream(file, "wt")) {
            "Unable to open ${entry.path} for writing"
        }
    }

    private fun ensureDirectory(segments: List<String>): Uri {
        if (segments.isEmpty()) return rootUri
        val key = segments.joinToString("/")
        directoryCache[key]?.let { return it }

        val parent = ensureDirectory(segments.dropLast(1))
        val name = segments.last()
        val directory = findChild(parent, name, directory = true)
            ?: DocumentsContract.createDocument(
                contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
            ?: error("Unable to create directory $key")

        directoryCache[key] = directory
        return directory
    }

    private fun findChild(parent: Uri, displayName: String, directory: Boolean): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                val mimeType = cursor.getString(mimeIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                if (name == displayName && isDirectory == directory) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }
}

private fun String.safeSegments(): List<String> =
    replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .also { segments ->
            require(segments.none { it == "." || it == ".." || it.contains('\u0000') }) {
                "Archive path escapes target directory"
            }
        }
