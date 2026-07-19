package io.github.rufid.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.rufid.core.SeekableByteSource
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

data class AndroidUriImageSource(
    val uri: Uri,
    val name: String,
    val size: Long,
) {
    fun open(contentResolver: ContentResolver): InputStream =
        requireNotNull(contentResolver.openInputStream(uri)) { "Unable to open $uri" }

    fun openSeekable(contentResolver: ContentResolver): AndroidSeekableByteSource =
        AndroidSeekableByteSource(
            requireNotNull(contentResolver.openFileDescriptor(uri, "r")) { "Unable to open $uri" },
            size,
        )

    companion object {
        fun from(contentResolver: ContentResolver, uri: Uri): AndroidUriImageSource {
            var name = uri.lastPathSegment ?: "selected-image"
            var size = -1L

            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (it != null && it.moveToFirst()) {
                    val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) name = it.getString(nameColumn)
                    if (sizeColumn >= 0) size = it.getLong(sizeColumn)
                }
            }

            return AndroidUriImageSource(uri, name, size)
        }
    }
}

class AndroidSeekableByteSource(
    private val descriptor: ParcelFileDescriptor,
    override val sizeBytes: Long,
) : SeekableByteSource, Closeable {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val channel = input.channel

    override fun readAt(byteOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), byteOffset)

    override fun close() {
        input.close()
        descriptor.close()
    }
}
