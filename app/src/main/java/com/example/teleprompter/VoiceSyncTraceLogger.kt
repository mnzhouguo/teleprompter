package com.example.teleprompter

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceSyncTraceLogger(context: Context) {

    companion object {
        private const val QUEUE_CAPACITY = 1024
        private const val MAX_TEXT_CHARS = 240
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val dropped = AtomicInteger(0)
    private val queue = ArrayBlockingQueue<Map<String, Any?>>(QUEUE_CAPACITY)
    private var worker: Thread? = null
    private var writer: BufferedWriter? = null

    val logFile: File

    init {
        val dir = File(appContext.getExternalFilesDir(null), "voice-sync-logs")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logFile = File(dir, "voice-sync-$stamp.jsonl")
    }

    fun start(session: Map<String, Any?> = emptyMap()) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ writeLoop() }, "VoiceSyncTraceLogger").apply {
            isDaemon = true
            start()
        }
        log("session_start", session + mapOf("path" to logFile.absolutePath))
    }

    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!running.get()) return
        val row = LinkedHashMap<String, Any?>()
        row["ts"] = System.currentTimeMillis()
        row["event"] = event
        row.putAll(fields)
        if (!queue.offer(row)) {
            dropped.incrementAndGet()
        }
    }

    fun stop(finalFields: Map<String, Any?> = emptyMap()) {
        if (!running.get()) return
        log("session_stop", finalFields)
        running.set(false)
        worker?.join(800)
        worker = null
    }

    private fun writeLoop() {
        writer = BufferedWriter(FileWriter(logFile, true), 32 * 1024)
        var writesSinceFlush = 0
        try {
            while (running.get() || queue.isNotEmpty()) {
                val row = queue.poll(250, TimeUnit.MILLISECONDS)
                if (row != null) {
                    val droppedCount = dropped.getAndSet(0)
                    if (droppedCount > 0) {
                        writeRow(mapOf("ts" to System.currentTimeMillis(), "event" to "logger_dropped", "count" to droppedCount))
                    }
                    writeRow(row)
                    writesSinceFlush++
                }
                if (writesSinceFlush >= 16 || (!running.get() && queue.isEmpty())) {
                    writer?.flush()
                    writesSinceFlush = 0
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VoiceSyncTrace", "日志写入失败: ${e.message}")
        } finally {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun writeRow(row: Map<String, Any?>) {
        val json = JSONObject()
        row.forEach { (key, value) ->
            json.put(key, sanitize(value))
        }
        writer?.append(json.toString())
        writer?.newLine()
    }

    private fun sanitize(value: Any?): Any? {
        return when (value) {
            is String -> if (value.length <= MAX_TEXT_CHARS) value else value.take(MAX_TEXT_CHARS) + "...[truncated]"
            is Number, is Boolean -> value
            is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }.mapValues { sanitize(it.value) })
            else -> value?.toString()
        }
    }
}
