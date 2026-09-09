package io.github.deserthouse.opticon.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TraceLogger —— Unified dual-write logging (Logcat + file)
 *
 * Five levels: VERBOSE, DEBUG, INFO, WARN, ERROR
 * Writes to files/logs/opticon.log in app private directory.
 */
object TraceLogger {

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private var currentLevel = INFO
    private var logFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, level: Int = INFO) {
        currentLevel = level
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "opticon.log")
        i("TraceLogger", "Initialized at level=$level, file=${logFile?.absolutePath}")
    }

    fun setLevel(level: Int) {
        currentLevel = level
    }

    fun v(tag: String, msg: String) = log(VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(INFO, tag, msg)
    fun w(tag: String, msg: String) = log(WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(ERROR, tag, msg, t)

    private fun log(level: Int, tag: String, msg: String, t: Throwable? = null) {
        if (level < currentLevel) return

        val levelChar = when (level) {
            VERBOSE -> 'V'
            DEBUG -> 'D'
            INFO -> 'I'
            WARN -> 'W'
            ERROR -> 'E'
            else -> '?'
        }

        val line = buildString {
            append(dateFormat.format(Date()))
            append(" ")
            append(levelChar)
            append("/")
            append(tag)
            append(": ")
            append(msg)
            if (t != null) {
                append("\n")
                append(Log.getStackTraceString(t))
            }
        }

        // Logcat
        when (level) {
            VERBOSE -> Log.v(tag, msg)
            DEBUG -> Log.d(tag, msg)
            INFO -> Log.i(tag, msg)
            WARN -> Log.w(tag, msg)
            ERROR -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }

        // File (async)
        val file = logFile ?: return
        scope.launch {
            try {
                FileWriter(file, true).use { it.appendLine(line) }
            } catch (_: Exception) { }
        }
    }
}
