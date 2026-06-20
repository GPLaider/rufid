package io.github.rufid.core

import android.content.Context
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LastErrorReport {
    private const val FILE_NAME = "last-error-report.txt"

    fun write(context: Context, operation: String, error: Throwable) {
        runCatching {
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use { writer ->
                writer.write(format(operation, error))
            }
        }
    }

    fun read(context: Context): String? =
        try {
            context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        }

    fun userMessage(error: Throwable): String =
        when (error) {
            is OperationCancelledException -> "Operation cancelled."
            is SecurityException -> "Permission was denied or expired. Re-select the file or request USB permission again."
            is IllegalArgumentException -> error.message ?: "The selected input is not valid for this operation."
            is IOException -> error.message ?: "The device, file, archive, or network stream could not be read or written."
            else -> error.message ?: error::class.java.simpleName
        }

    private fun format(operation: String, error: Throwable): String {
        val stackTrace = StringWriter().also { buffer ->
            error.printStackTrace(PrintWriter(buffer))
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("Rufid last error report")
            appendLine("Time: $timestamp")
            appendLine("Operation: $operation")
            appendLine("Type: ${error::class.java.name}")
            appendLine("Message: ${error.message ?: "(no message)"}")
            appendLine()
            append(stackTrace)
        }
    }
}
