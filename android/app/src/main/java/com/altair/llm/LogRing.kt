package com.altair.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-app log ring buffer so the user can copy real diagnostics without
 * adb/logcat. Fed by the load pipeline, probe results and server events;
 * surfaced in Settings → Diagnostics.
 */
object LogRing {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        android.util.Log.i("Altair", line)
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
