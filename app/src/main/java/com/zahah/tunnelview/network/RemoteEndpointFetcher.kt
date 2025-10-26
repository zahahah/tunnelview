package com.zahah.tunnelview.network

import com.zahah.tunnelview.data.ProxyEndpoint
import com.zahah.tunnelview.data.ProxyEndpointSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class RemoteEndpointFetcher(
    private val client: OkHttpClient,
) {

    suspend fun fetch(url: String, accessKey: String?): RemoteEndpointResult =
        withContext(Dispatchers.IO) {
            val request = buildRequest(url, accessKey)
            runCatching { client.newCall(request).execute() }
                .fold(
                    onSuccess = { response -> response.use(::handleResponse) },
                    onFailure = { error -> RemoteEndpointResult.NetworkError(error) }
                )
        }

    private fun buildRequest(url: String, accessKey: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
        accessKey?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { key ->
                val headerValue = when {
                    key.startsWith("Bearer ", ignoreCase = true) -> key
                    key.startsWith("Token ", ignoreCase = true) -> key
                    else -> "Bearer $key"
                }
                builder.header("Authorization", headerValue)
            }
        return builder.build()
    }

    private fun handleResponse(response: Response): RemoteEndpointResult {
        if (response.code == 401 || response.code == 403) {
            return RemoteEndpointResult.AuthError(response.code)
        }
        if (!response.isSuccessful) {
            return RemoteEndpointResult.HttpError(response.code, response.message)
        }
        val body = response.body?.string()?.trim()
            ?: return RemoteEndpointResult.Empty

        val firstLine = body.lineSequence().firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return RemoteEndpointResult.Empty

        val endpoint = ProxyEndpoint.parseFlexible(firstLine, ProxyEndpointSource.FALLBACK)
            ?: return RemoteEndpointResult.InvalidFormat(firstLine)

        return RemoteEndpointResult.Success(endpoint)
    }
}

sealed class RemoteEndpointResult {
    data class Success(val endpoint: ProxyEndpoint) : RemoteEndpointResult()
    data class HttpError(val code: Int, val message: String?) : RemoteEndpointResult()
    data class InvalidFormat(val payload: String) : RemoteEndpointResult()
    data class AuthError(val code: Int) : RemoteEndpointResult()
    data class NetworkError(val throwable: Throwable) : RemoteEndpointResult()
    object Empty : RemoteEndpointResult()
}
