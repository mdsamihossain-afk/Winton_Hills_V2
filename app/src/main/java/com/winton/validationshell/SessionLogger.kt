package com.winton.validationshell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEvent(
    val timestamp: Long,
    val elapsedNanos: Long,
    val eventType: String,
    val message: String,
    val data: Map<String, String> = emptyMap()
)

class SessionLogger {
    private val logEntries = ConcurrentLinkedQueue<LogEvent>()
    var sessionId: String = UUID.randomUUID().toString().take(8)
        private set

    /** Call at engine start to reset the session. */
    fun startSession() {
        sessionId = UUID.randomUUID().toString().take(8)
        logEntries.clear()
    }

    fun log(eventType: String, message: String, data: Map<String, String> = emptyMap()) {
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            elapsedNanos = SystemClock.elapsedRealtimeNanos(),
            eventType = eventType,
            message = message,
            data = data
        )
        logEntries.add(event)
    }

    /**
     * Append a structured analysis row. Called at ~1 Hz from the polling loop.
     */
    fun appendAnalysisRow(
        rms: Float,
        centroid: Float,
        classification: Int,
        confidence: Float,
        policyId: Int,
        latencyMs: Float,
        outputRoute: String
    ) {
        log("ANALYSIS", "Frame", mapOf(
            "SessionID" to sessionId,
            "RMS" to "%.4f".format(rms),
            "Centroid" to "%.4f".format(centroid),
            "Classification" to classification.toString(),
            "Confidence" to "%.2f".format(confidence),
            "PolicyID" to policyId.toString(),
            "LatencyMs" to "%.2f".format(latencyMs),
            "OutputRoute" to outputRoute
        ))
    }

    fun clear() {
        logEntries.clear()
    }

    fun createCsv(createFileLauncher: ActivityResultLauncher<Intent>) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "session_${sessionId}_$timestamp.csv")
        }
        createFileLauncher.launch(intent)
    }

    fun writeCsvToUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeCsv(outputStream)
            }
        } catch (e: Exception) {
            android.util.Log.e("SessionLogger", "Failed to write CSV", e)
        }
    }

    private fun writeCsv(outputStream: OutputStream) {
        val writer = outputStream.bufferedWriter()
        // Header
        writer.write("Timestamp,ElapsedNanos,SessionID,EventType,Message,Details\n")
        // Data
        logEntries.forEach { event ->
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(event.timestamp))
            val details = event.data.entries.joinToString("; ") { "${it.key}=${it.value}" }
            writer.write("\"$timestamp\",${event.elapsedNanos},\"$sessionId\",\"${event.eventType}\",\"${event.message}\",\"$details\"\n")
        }
        writer.flush()
    }

    /** Get the number of logged events. */
    fun getEventCount(): Int = logEntries.size
}
