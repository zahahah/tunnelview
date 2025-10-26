package com.zahah.tunnelview.logging

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Lightweight structured logger tailored for connection diagnostics.
 *
 * Maintains a ring buffer of recent [ConnEvent] instances in memory and mirrors them to
 * a small JSONL file under app-private storage so that diagnostics survive process restarts.
 */
class ConnLogger private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val eventBuffer: ArrayDeque<ConnEvent> = ArrayDeque(MAX_EVENTS)
    private val _events = MutableStateFlow<List<ConnEvent>>(emptyList())
    val events: StateFlow<List<ConnEvent>> = _events.asStateFlow()
    private val storageFile: File = File(appContext.filesDir, STORAGE_FILE_NAME)

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    suspend fun log(event: ConnEvent) {
        lock.withLock {
            if (eventBuffer.size == MAX_EVENTS) {
                eventBuffer.removeFirst()
            }
            eventBuffer.addLast(event)
            _events.value = eventBuffer.toList()
        }
        scope.launch {
            persist()
        }
        logcat(event)
    }

    fun snapshot(): List<ConnEvent> = _events.value

    private suspend fun loadFromDisk() {
        if (!storageFile.exists() || storageFile.length() <= 0L) {
            return
        }
        val contents = runCatching {
            FileInputStream(storageFile).use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        if (contents.isBlank()) return

        val restored = runCatching {
            JSONArray(contents).let { array ->
                (0 until min(array.length(), MAX_EVENTS)).mapNotNull { index ->
                    array.optJSONObject(array.length() - 1 - index)?.let { json ->
                        jsonToEvent(json)
                    }
                }.asReversed()
            }
        }.getOrElse { emptyList() }

        if (restored.isNotEmpty()) {
            lock.withLock {
                eventBuffer.clear()
                eventBuffer.addAll(restored.takeLast(MAX_EVENTS))
                _events.value = eventBuffer.toList()
            }
        }
    }

    private suspend fun persist() {
        lock.withLock {
            val payload = JSONArray().apply {
                eventBuffer.forEach { put(eventToJson(it)) }
            }
            runCatching {
                if (!storageFile.exists()) {
                    storageFile.parentFile?.mkdirs()
                    storageFile.createNewFile()
                }
                FileOutputStream(storageFile, false).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun eventToJson(event: ConnEvent): JSONObject = JSONObject().apply {
        put("timestampMillis", event.timestampMillis)
        put("level", event.level.name)
        put("phase", event.phase.name)
        put("message", event.message)
        event.endpoint?.let { endpoint ->
            put("endpointHost", endpoint.host)
            put("endpointPort", endpoint.port)
            put("endpointSource", endpoint.source.name)
        }
        event.attempt?.let { put("attempt", it) }
        event.durationMillis?.let { put("durationMillis", it) }
        event.throwableClass?.let { put("throwableClass", it) }
        event.throwableMessage?.let { put("throwableMessage", it) }
        event.stacktracePreview?.let { put("stacktracePreview", it) }
    }

    private fun jsonToEvent(json: JSONObject): ConnEvent = ConnEvent(
        timestampMillis = json.optLong("timestampMillis"),
        level = runCatching { ConnEvent.Level.valueOf(json.optString("level")) }.getOrDefault(ConnEvent.Level.INFO),
        phase = runCatching { ConnEvent.Phase.valueOf(json.optString("phase")) }.getOrDefault(ConnEvent.Phase.OTHER),
        message = json.optString("message"),
        endpoint = json.optString("endpointHost")
            .takeIf { it.isNotBlank() }
            ?.let { host ->
                val port = json.optInt("endpointPort")
                val sourceName = json.optString("endpointSource")
                val source = runCatching { com.zahah.tunnelview.data.ProxyEndpointSource.valueOf(sourceName) }.getOrNull()
                if (port > 0 && source != null) {
                    runCatching { com.zahah.tunnelview.data.ProxyEndpoint(host, port, source) }.getOrNull()
                } else {
                    null
                }
            },
        attempt = json.optInt("attempt").takeIf { json.has("attempt") },
        durationMillis = json.optLong("durationMillis").takeIf { json.has("durationMillis") },
        throwableClass = json.optString("throwableClass").takeIf { it.isNotBlank() },
        throwableMessage = json.optString("throwableMessage").takeIf { it.isNotBlank() },
        stacktracePreview = json.optString("stacktracePreview").takeIf { it.isNotBlank() },
    )

    private fun logcat(event: ConnEvent) {
        val tag = LOG_TAG
        val msg = buildString {
            append("[${event.phase}] ${event.message}")
            event.endpoint?.let { append(" @ ${it.host}:${it.port}") }
            event.attempt?.let { append(" attempt=$it") }
            event.durationMillis?.let { append(" duration=${it}ms") }
            if (!event.throwableMessage.isNullOrBlank()) {
                append(" :: ${event.throwableMessage}")
            }
        }
        val level = when (event.level) {
            ConnEvent.Level.DEBUG -> Log.DEBUG
            ConnEvent.Level.INFO -> Log.INFO
            ConnEvent.Level.WARN -> Log.WARN
            ConnEvent.Level.ERROR -> Log.ERROR
        }
        Log.println(level, tag, msg)
        if (!event.stacktracePreview.isNullOrBlank() && event.level == ConnEvent.Level.ERROR) {
            Log.println(level, tag, event.stacktracePreview)
        }
    }

    companion object {
        private const val LOG_TAG = "ConnLogger"
        private const val MAX_EVENTS = 200
        private const val STORAGE_FILE_NAME = "connection_events.json"

        @Volatile
        private var instance: ConnLogger? = null

        fun getInstance(context: Context): ConnLogger {
            return instance ?: synchronized(this) {
                instance ?: ConnLogger(context).also { instance = it }
            }
        }

        fun platformInfo(): String = buildString {
            append("SDK=").append(Build.VERSION.SDK_INT)
            append(" Manufacturer=").append(Build.MANUFACTURER)
            append(" Model=").append(Build.MODEL)
        }
    }
}
