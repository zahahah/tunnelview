package com.zahah.tunnelview.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.zahah.tunnelview.storage.CredentialsStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Centralized OkHttp client that guarantees the authentication header, sane timeouts,
 * redirect support and persistence of cookies shared with the WebView layer.
 */
object HttpClient {

    @Volatile
    private var instance: OkHttpClient? = null

    fun shared(context: Context): OkHttpClient {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): OkHttpClient {
        val credentialsStore = CredentialsStore.getInstance(appContext)
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(WebViewCookieJar())
            .addInterceptor(AuthHeaderInterceptor { credentialsStore.httpHeaderConfig() })
            .build()
    }

    private class WebViewCookieJar : CookieJar {
        private val cookieManager: CookieManager = CookieManager.getInstance()
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieHeader = runOnMainThread { cookieManager.getCookie(url.toString()) } ?: return emptyList()
            if (cookieHeader.isBlank()) return emptyList()
            return cookieHeader.split(';')
                .mapNotNull { token ->
                    val trimmed = token.trim()
                    if (trimmed.isEmpty()) {
                        null
                    } else {
                        kotlin.runCatching { Cookie.parse(url, trimmed) }.getOrNull()
                    }
                }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            runOnMainThread {
                cookies.forEach { cookie ->
                    cookieManager.setCookie(url.toString(), cookie.toString())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cookieManager.flush()
                }
            }
        }

        private fun <T> runOnMainThread(block: () -> T): T? {
            return if (Looper.myLooper() == Looper.getMainLooper()) {
                kotlin.runCatching(block).getOrNull()
            } else {
                val latch = CountDownLatch(1)
                var result: T? = null
                var error: Throwable? = null
                mainHandler.post {
                    try {
                        result = block()
                    } catch (t: Throwable) {
                        error = t
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()
                if (error != null) {
                    throw error as Throwable
                }
                result
            }
        }
    }
}
