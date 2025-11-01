package com.zahah.tunnelview.logging

import com.zahah.tunnelview.data.ProxyEndpoint

/**
 * Structured representation of a connection related event emitted by the SSH tunnel layer.
 *
 * Stored both in-memory (ring buffer) and persisted lightly to disk for diagnostics.
 */
data class ConnEvent(
    val timestampMillis: Long,
    val level: Level,
    val phase: Phase,
    val message: String,
    val endpoint: ProxyEndpoint? = null,
    val attempt: Int? = null,
    val durationMillis: Long? = null,
    val throwableClass: String? = null,
    val throwableMessage: String? = null,
    val stacktracePreview: String? = null,
) {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    enum class Phase {
        DNS,
        HTTP,
        TCP,
        SSH_HANDSHAKE,
        AUTH,
        FORWARD,
        KEEPALIVE,
        TEARDOWN,
        OTHER,
    }
}
