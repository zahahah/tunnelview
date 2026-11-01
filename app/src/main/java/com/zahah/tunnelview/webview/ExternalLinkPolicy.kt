package com.zahah.tunnelview.webview

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal object ExternalLinkPolicy {
    private val internalSchemes = setOf("about", "data", "blob", "javascript", "file", "content")
    fun isWebUrl(url: String): Boolean =
        url.substringBefore(':', "").lowercase(Locale.ROOT) in setOf("http", "https")

    fun isExternal(url: String): Boolean {
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
