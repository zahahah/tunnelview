package com.zahah.tunnelview.webview

/** Keeps automatic cross-origin fallbacks from replacing a page during an external approval. */
internal class ExternalHandoffGuard {
    private var page: String? = null
    private var startedAt = 0L

    fun begin(pageUrl: String?, now: Long) {
        page = pageUrl?.takeIf { ExternalLinkPolicy.isWebUrl(it) }
        startedAt = now
    }

    fun clear() { page = null }

    fun active(now: Long): Boolean = page != null && now - startedAt in 0..300_000L

    fun blocks(url: String, mainFrame: Boolean, gesture: Boolean, now: Long): Boolean =
        active(now) && mainFrame && !gesture && ExternalLinkPolicy.isWebUrl(url) &&
            !ExternalLinkPolicy.sameOrigin(page.orEmpty(), url)
}
