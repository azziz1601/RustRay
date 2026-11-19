package io.rustray.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        entries.addLast("${clock.format(Date())}  $message")
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        if (entries.isEmpty()) "No events yet." else entries.joinToString("\n")
}
