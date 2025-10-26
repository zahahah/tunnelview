package com.zahah.tunnelview.network

import android.util.Log
import com.zahah.tunnelview.data.ProxyEndpoint
import com.zahah.tunnelview.data.ProxyEndpointSource
import com.zahah.tunnelview.data.ProxyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class NtfySubscriber(
    private val client: OkHttpClient,
    private val repository: ProxyRepository,
    private val externalScope: CoroutineScope? = null,
) {

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusFlowInternal = MutableSharedFlow<NtfyStatus>(extraBufferCapacity = 8)
    val statusFlow: SharedFlow<NtfyStatus> = statusFlowInternal.asSharedFlow()

    private var currentTopicOrUrl: String? = null
    private var reconnectJob: Job? = null
    private var webSocket: WebSocket? = null
    private var lastUrl: String? = null

    fun start(topicOrUrl: String) {
        val trimmed = topicOrUrl.trim()
        if (trimmed.isEmpty()) {
            statusFlowInternal.tryEmit(NtfyStatus.Error("Tópico do ntfy vazio"))
            return
        }
        if (trimmed == currentTopicOrUrl && webSocket != null) {
            Log.d(TAG, "ntfy subscriber already active for $trimmed")
            return
        }
        stop()
        currentTopicOrUrl = trimmed
        val endpoint = buildWebSocketUrl(trimmed)
        if (endpoint == null) {
            statusFlowInternal.tryEmit(NtfyStatus.Error("URL/tópico inválido: $trimmed"))
            return
        }
        statusFlowInternal.tryEmit(NtfyStatus.Connecting(endpoint))
        connect(endpoint)
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.cancel()
        webSocket = null
        lastUrl = null
    }

    fun close() {
        stop()
        if (externalScope == null) {
            scope.cancel()
        }
    }

    private fun connect(url: String) {
        lastUrl = url
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                statusFlowInternal.tryEmit(NtfyStatus.Connected)
                Log.d(TAG, "Connected to ntfy stream")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handlePayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handlePayload(bytes.utf8())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                statusFlowInternal.tryEmit(NtfyStatus.Disconnected(code, reason))
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                statusFlowInternal.tryEmit(
                    NtfyStatus.Error(
                        t.message ?: "Falha na conexão ntfy"
                    )
                )
                scheduleReconnect()
            }
        })
    }

    private fun handlePayload(payload: String) {
        val endpoint = ProxyEndpoint.parseFlexible(payload, ProxyEndpointSource.NTFY)
        if (endpoint != null) {
            repository.updateEndpoint(endpoint)
        } else {
            statusFlowInternal.tryEmit(NtfyStatus.IgnoredPayload(payload.take(64)))
            repository.reportError("Payload ntfy ignorado")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val url = lastUrl ?: return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!isActive) return@launch
            statusFlowInternal.tryEmit(NtfyStatus.Retrying)
            connect(url)
        }
    }

    private fun buildWebSocketUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)
        ) {
            return ensureSuffix(trimmed, "/ws")
        }
        val hasScheme = trimmed.contains("://")
        val candidate = when {
            hasScheme -> trimmed
            trimmed.contains(".") && trimmed.contains("/") -> "https://$trimmed"
            trimmed.contains(".") && !trimmed.contains("/") -> "https://$trimmed"
            trimmed.contains("/") -> "https://ntfy.sh/$trimmed"
            else -> "https://ntfy.sh/$trimmed"
        }
        val uri = runCatching { android.net.Uri.parse(candidate) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            "ws" -> "ws"
            "wss" -> "wss"
            else -> "wss"
        }
        val path = (uri.encodedPath ?: "").trimEnd('/')
        val resolvedPath = when {
            path.endsWith("/ws") -> path
            path.endsWith("/ws/") -> path.removeSuffix("/") // unlikely but guard
            path.endsWith("/sse") -> path.removeSuffix("/sse") + "/ws"
            path.isEmpty() -> "/ws"
            else -> "$path/ws"
        }
        val sb = StringBuilder()
        sb.append(scheme)
        sb.append("://")
        sb.append(host)
        if (uri.port != -1) {
            sb.append(':').append(uri.port)
        }
        sb.append(resolvedPath)
        val query = uri.encodedQuery
        if (!query.isNullOrEmpty()) {
            sb.append('?').append(query)
        }
        return sb.toString()
    }

    private fun ensureSuffix(value: String, suffix: String): String {
        val normalized = value.trimEnd('/')
        return if (normalized.endsWith(suffix)) normalized else "$normalized$suffix"
    }

    companion object {
        private const val TAG = "NtfySubscriber"
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}

sealed class NtfyStatus {
    data class Connecting(val url: String) : NtfyStatus()
    object Connected : NtfyStatus()
    data class Disconnected(val code: Int, val reason: String?) : NtfyStatus()
    object Retrying : NtfyStatus()
    data class IgnoredPayload(val payload: String) : NtfyStatus()
    data class Error(val message: String) : NtfyStatus()
}
