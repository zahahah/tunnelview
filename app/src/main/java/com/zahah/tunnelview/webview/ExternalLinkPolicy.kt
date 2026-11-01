package com.zahah.tunnelview.webview

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal object ExternalLinkPolicy {
    /**
     * Rabby's /mobile/wc URL is an Android app link, not a web document.
     * Bypass HTTPS app-link verification/browser fallbacks using its supported native scheme.
     * Keep the pairing query encoded exactly as supplied by the dapp.
     */
    fun externalTarget(url: String): String = runCatching {
        val uri = URI(url)
        val rabbyPairingLink = uri.scheme.equals("https", true) &&
            uri.host.equals("go.rabby.io", true) && uri.rawUserInfo == null &&
            uri.port in setOf(-1, 443) && uri.rawPath in setOf("/mobile/wc", "/mobile/wc/")
        if (!rabbyPairingLink) return@runCatching url
        buildString {
            append("rabby://wc")
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }.getOrDefault(url)

    private val internalSchemes = setOf("about", "data", "blob", "javascript", "file", "content")
    fun isWebUrl(url: String): Boolean =
        url.substringBefore(':', "").lowercase(Locale.ROOT) in setOf("http", "https")

    fun isExternal(url: String): Boolean {
        if (externalTarget(url) != url) return true
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme.isEmpty() || scheme in internalSchemes) return false
        if (!isWebUrl(url)) return true
        // Wallet universal links can otherwise display the wallet site's storage error page.
        return runCatching {
            URI(url).rawQuery.orEmpty().split('&').any {
                val key = it.substringBefore('=')
                val value = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                key == "uri" && value.startsWith("wc:", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    fun isSafeIntentTarget(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        return scheme.matches(Regex("[a-z][a-z0-9+.-]*")) &&
            scheme !in internalSchemes && scheme !in setOf("intent", "android-app")
    }

    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = URI(first)
        val b = URI(second)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        isWebUrl(first) && isWebUrl(second) && a.host != null &&
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }.getOrDefault(false)
}
