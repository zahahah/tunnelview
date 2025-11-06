package com.zahah.tunnelview.proxy

import android.content.Context
import android.net.Uri
import com.zahah.tunnelview.network.HttpClient
import com.zahah.tunnelview.storage.CredentialsStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.net.ServerSocket
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response as OkHttpResponse

/**
 * Lightweight loopback proxy that injects the mandatory authentication header into every request
 * issued by the WebView when CSP/CORS restrictions prevent JavaScript shims from doing so.
 */
class LocalHeaderProxy(
    context: Context,
    private val credentialsStore: CredentialsStore = CredentialsStore.getInstance(context.applicationContext),
    private val client: OkHttpClient = HttpClient.shared(context.applicationContext)
) {

    @Volatile
    private var server: ProxyServer? = null

    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        if (server != null) return server!!.listeningPort
        val port = if (preferredPort > 0) preferredPort else findFreePort()
        val currentServer = ProxyServer(port)
        currentServer.start(SOCKET_TIMEOUT, false)
        server = currentServer
        return currentServer.listeningPort
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun proxyUrlFor(targetUrl: String): String {
        val encoded = Uri.encode(targetUrl)
        val activePort = server?.listeningPort ?: error("LocalHeaderProxy not started")
        return "http://127.0.0.1:$activePort/$encoded"
    }

    private inner class ProxyServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            val target = resolveTarget(session) ?: return errorResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "Missing target URL"
            )
            val authHeader = credentialsStore.httpHeaderConfig()
                ?: return errorResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "Missing authentication header"
                )
            return runCatching {
                val upstreamRequest = buildUpstreamRequest(session, target, authHeader)
                val upstreamResponse = client.newCall(upstreamRequest).execute()
                toNanoResponse(upstreamResponse)
            }.getOrElse { error ->
                errorResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    error.message ?: "Proxy error"
                )
            }
        }

        private fun buildUpstreamRequest(
            session: IHTTPSession,
            targetUrl: String,
            authHeader: CredentialsStore.HeaderConfig
        ): Request {
            val builder = Request.Builder().url(targetUrl)
            session.headers.forEach { (name, value) ->
                if (name != null && shouldForwardHeader(name, authHeader.name)) {
                    builder.header(name, value)
                }
            }
            builder.header(authHeader.name, authHeader.value)
            val bodyBytes = readBodyBytes(session)
            val body = createRequestBody(session.method, bodyBytes, session.headers["content-type"])
            return builder.method(session.method.name, body).build()
        }

        private fun toNanoResponse(upstream: OkHttpResponse): NanoHTTPD.Response {
            val status = NanoHTTPD.Response.Status.lookup(upstream.code) ?: NanoHTTPD.Response.Status.OK
            val body = upstream.body
            val contentType = body?.contentType()?.toString()
                ?: upstream.header("Content-Type")
                ?: NanoHTTPD.MIME_PLAINTEXT
            val nanoResponse = if (body != null) {
                val stream = object : FilterInputStream(body.byteStream()) {
                    override fun close() {
                        super.close()
                        upstream.close()
                    }
                }
                newChunkedResponse(status, contentType, stream)
            } else {
                upstream.close()
                newFixedLengthResponse(status, contentType, "")
            }
            for (i in 0 until upstream.headers.size) {
                val name = upstream.headers.name(i)
                val value = upstream.headers.value(i)
                nanoResponse.addHeader(name, value)
            }
            applyCorsHeaders(nanoResponse)
            return nanoResponse
        }

        private fun resolveTarget(session: IHTTPSession): String? {
            val param = session.parameters["target"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            val decoded = when {
                !param.isNullOrBlank() -> Uri.decode(param)
                else -> {
                    val path = session.uri.removePrefix("/").takeIf { it.isNotBlank() } ?: return null
                    Uri.decode(path)
                }
            }
            val parsed = runCatching { Uri.parse(decoded) }.getOrNull()
            return if (parsed?.scheme.isNullOrBlank()) null else decoded
        }

        private fun shouldForwardHeader(name: String, authName: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return lower != "host" &&
                lower != "content-length" &&
                lower != "accept-encoding" &&
                lower != authName.lowercase(Locale.US)
        }

        private fun readBodyBytes(session: IHTTPSession): ByteArray? {
            val methodHasBody = session.method in METHODS_WITH_BODY
            if (!methodHasBody) return null
            val input = session.inputStream ?: return ByteArray(0)
            val buffer = ByteArrayOutputStream()
            input.copyTo(buffer)
            return buffer.toByteArray()
        }

        private fun createRequestBody(
            method: Method,
            bytes: ByteArray?,
            contentType: String?
        ): RequestBody? {
            if (method !in METHODS_WITH_BODY) return null

            val safeBytes = bytes ?: ByteArray(0)
            val mediaType = contentType?.toMediaTypeOrNull()
            return safeBytes.toRequestBody(mediaType)
        }

        private fun applyCorsHeaders(response: NanoHTTPD.Response) {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Credentials", "true")
            response.addHeader(
                "Access-Control-Allow-Headers",
                "Origin, Content-Type, Accept, Authorization, X-Requested-With"
            )
            response.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
        }

        private fun errorResponse(status: NanoHTTPD.Response.Status, message: String): NanoHTTPD.Response {
            return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message).also {
                applyCorsHeaders(it)
            }
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val SOCKET_TIMEOUT = 5_000
        private val METHODS_WITH_BODY = setOf(
            Method.POST,
            Method.PUT,
            Method.DELETE,
            Method.PATCH,
            Method.OPTIONS
        )
    }
}
