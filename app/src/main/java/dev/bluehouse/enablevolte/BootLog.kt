package dev.bluehouse.enablevolte

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent file-based logger for boot-time operations.
 * Writes timestamped entries to filesDir/boot_log.txt.
 * Thread-safe. Viewable from the app Home screen.
 */
object BootLog {
    const val FILE_NAME = "boot_log.txt"

    // Keep at most this many characters to avoid unbounded growth.
    private const val MAX_CHARS = 32_000

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            // Trim oldest half when the file gets too big.
            if (file.exists() && file.length() > MAX_CHARS) {
                val text = file.readText()
                val trimmed = text.drop(text.length / 2)
                // Find the next newline so we don't start mid-line.
                val nl = trimmed.indexOf('\n')
                file.writeText(if (nl >= 0) trimmed.drop(nl + 1) else trimmed)
            }
            val entry = "${fmt.format(Date())} [$tag] $message\n"
            FileWriter(file, /* append = */ true).use { it.write(entry) }
            Log.d(tag, message)
        } catch (e: Exception) {
            Log.e("BootLog", "Failed to write: $message", e)
        }
    }

    /** Appends an exception with its stack trace. */
    @Synchronized
    fun appendError(context: Context, tag: String, message: String, e: Throwable) {
        append(context, tag, "$message: ${e.javaClass.simpleName}: ${e.message}")
        append(context, tag, "  at ${e.stackTrace.take(5).joinToString("\n  at ")}")
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            Log.e("BootLog", "Failed to clear", e)
        }
    }

    fun read(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > 0) file.readText()
            else "(no boot log yet — enable Auto-apply and reboot)"
        } catch (e: Exception) {
            "(error reading log: ${e.message})"
        }
    }
}
