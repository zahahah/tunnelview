package com.zahah.tunnelview.network

import com.zahah.tunnelview.storage.CredentialsStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Forces every request executed by the shared [okhttp3.OkHttpClient] to carry the
 * mandatory HTTP authentication header without touching the payload or HTTP method.
 */
class AuthHeaderInterceptor(
    private val provider: () -> CredentialsStore.HeaderConfig?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val header = provider()?.let { config ->
            val name = config.name.trim()
            val value = config.value.trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        } ?: return chain.proceed(original)

        if (original.header(header.first) == header.second) {
            return chain.proceed(original)
        }

        val updated = original.newBuilder()
            .header(header.first, header.second)
            .method(original.method, original.body)
            .build()
        return chain.proceed(updated)
    }
}
