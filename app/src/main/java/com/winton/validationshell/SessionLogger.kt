package com.winton.validationshell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEvent(
    val timestamp: Long,
    val eventType: String,
    val message: String,
    val data: Map<String, String> = emptyMap()
)

class SessionLogger {
    private val logEntries = mutableListOf<LogEvent>()

    fun log(eventType: String, message: String, data: Map<String, String> = emptyMap()) {
        val event = LogEvent(System.currentTimeMillis(), eventType, message, data)
        logEntries.add(event)
    }

    fun clear() {
        logEntries.clear()
    }

    fun createCsv(createFileLauncher: ActivityResultLauncher<Intent>) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "session_logs_$timestamp.csv")
        }
        createFileLauncher.launch(intent)
    }

    fun writeCsvToUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeCsv(outputStream)
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    private fun writeCsv(outputStream: OutputStream) {
        val writer = outputStream.bufferedWriter()
        // Header
        writer.write("Timestamp,EventType,Message,Details\n")
        // Data
        logEntries.forEach { event ->
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(event.timestamp))
            val details = event.data.entries.joinToString("; ") { "${it.key}=${it.value}" }
            writer.write("\"$timestamp\",\"${event.eventType}\",\"${event.message}\",\"$details\"\n")
        }
        writer.flush()
    }
}
