package io.rustray.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_ENTRIES = 160
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var logFile: File? = null
    private val memoryFallback = ArrayDeque<String>()

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.applicationContext.cacheDir, "event_log.txt")
        }
    }

    @Synchronized
    fun add(message: String) {
        val line = "${clock.format(Date())}  $message"
        val file = logFile
        if (file == null) {
            memoryFallback.addLast(line)
            while (memoryFallback.size > MAX_ENTRIES) memoryFallback.removeFirst()
            return
        }
        runCatching {
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            lines.add(line)
            val trimmed = if (lines.size > MAX_ENTRIES) lines.takeLast(MAX_ENTRIES) else lines
            val tmp = File(file.parentFile, "event_log.txt.tmp")
            tmp.writeText(trimmed.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(trimmed.joinToString("\n"))
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun dump(): String {
        val file = logFile
        if (file != null && file.exists()) {
            val content = runCatching { file.readText().trim() }.getOrDefault("")
            if (content.isNotEmpty()) return content
        }
        return if (memoryFallback.isEmpty()) "No events yet." else memoryFallback.joinToString("\n")
    }
}
