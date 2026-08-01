package com.github.vermilion10.disqrpc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val message: String
    )

    fun log(message: String, level: String = "INFO") {
        // Optimization: Truncate very long messages to prevent UI jank
        val displayMessage = if (message.length > 1000) message.take(1000) + "... (truncated)" else message
        
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            message = displayMessage
        )
        // Optimization: Batch updates if logs come in too fast
        _logs.update { currentLogs -> 
            val newLogs = currentLogs + entry
            if (newLogs.size > 200) newLogs.drop(newLogs.size - 200) else newLogs
        }
        android.util.Log.d("disqRPC", "[$level] $message")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun i(message: String) = log(message, "INFO")
    fun e(message: String) = log(message, "ERROR")
    fun d(message: String) = log(message, "DEBUG")
    fun w(message: String) = log(message, "WARN")
}
