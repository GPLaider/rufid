package io.github.rufid.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

data class AndroidUriImageSource(
    val uri: Uri,
    val name: String,
    val size: Long,
) {
    fun open(contentResolver: ContentResolver): InputStream =
        requireNotNull(contentResolver.openInputStream(uri)) { "Unable to open $uri" }

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

