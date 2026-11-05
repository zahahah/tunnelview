package com.zahah.tunnelview.ssh

/** Limits automatic missing-endpoint refreshes; explicit refresh requests remain unaffected. */
internal class EndpointRefreshGate {
    private var lastRefreshAt: Long? = null

    fun shouldRefresh(now: Long): Boolean {
        val last = lastRefreshAt
        if (last != null && now >= last && now - last < INTERVAL_MS) return false
        lastRefreshAt = now
        return true
    }

    fun reset() { lastRefreshAt = null }

    companion object { private const val INTERVAL_MS = 15_000L }
}
