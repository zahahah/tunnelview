package com.zahah.tunnelview.network

/** Bounded retry delays; successful connections immediately restore the initial delay. */
internal class ReconnectBackoff {
    private var delayMs = INITIAL_MS

    @Synchronized fun next(): Long {
        val current = delayMs
        delayMs = (delayMs * 2).coerceAtMost(MAX_MS)
        return current
    }

    @Synchronized fun reset() { delayMs = INITIAL_MS }

    companion object {
        private const val INITIAL_MS = 5_000L
        private const val MAX_MS = 30_000L
    }
}
