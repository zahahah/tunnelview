package com.zahah.tunnelview.data

import android.net.Uri

/**
 * Describes the remote TCP endpoint that the SSH tunnelling layer should target.
 */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val source: ProxyEndpointSource,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {

    init {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port $port out of range" }
    }

    fun asTcpUrl(): String = "tcp://$host:$port"

    companion object {
        private const val TCP_SCHEME = "tcp://"

        fun parse(value: String, source: ProxyEndpointSource): ProxyEndpoint? {
            val normalized = normalize(value) ?: return null
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            val host = uri.host?.trim().orEmpty()
            val port = uri.port
            if (host.isBlank() || port <= 0) return null
            return ProxyEndpoint(host, port, source)
        }

        fun parseFlexible(value: String, source: ProxyEndpointSource): ProxyEndpoint? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return ProxyEndpointJsonParser.parse(trimmed, source)
            }

            val tcpCandidate = if (trimmed.startsWith(TCP_SCHEME, ignoreCase = true)) {
                trimmed
            } else {
                "$TCP_SCHEME$trimmed"
            }
            parse(tcpCandidate, source)?.let { return it }

            // As a last resort, try to find an embedded tcp://host:port inside the payload
            val matcher = TCP_PATTERN.find(trimmed)
            if (matcher != null) {
                val host = matcher.groupValues[1]
                val port = matcher.groupValues.getOrNull(2)?.toIntOrNull()
                if (!host.isNullOrBlank() && port != null && port in 1..65535) {
                    return ProxyEndpoint(host, port, source)
                }
            }
            return null
        }

        private fun normalize(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith(TCP_SCHEME, ignoreCase = true)) {
                return trimmed
            }
            return TCP_SCHEME + trimmed.removePrefix("tcp://")
        }

        private val TCP_PATTERN =
            Regex("tcp://([A-Za-z0-9._\\-]+?)(?::(\\d{1,5}))?(?:\\s|\$|/)")
    }
}

enum class ProxyEndpointSource {
    MANUAL,
    NTFY,
    FALLBACK,
    DEFAULT,
}

/**
 * Helper that understands the basic JSON payloads we receive from ntfy / remote file.
 */
private object ProxyEndpointJsonParser {
    fun parse(json: String, source: ProxyEndpointSource): ProxyEndpoint? {
        val data = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        return parseJsonObject(data, source)
    }

    private val hostKeys = setOf(
        "host", "hostname", "ssh_host", "remote_host", "address", "endpoint", "url", "public_host"
    )
    private val portKeys = setOf(
        "port", "ssh_port", "remote_port", "endpoint_port", "public_port", "tunnel_port"
    )

    private fun parseJsonObject(obj: org.json.JSONObject, source: ProxyEndpointSource): ProxyEndpoint? {
        var hostCandidate: String? = null
        var portCandidate: Int? = null

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            when (value) {
                is org.json.JSONObject -> parseJsonObject(value, source)?.let { return it }
                is org.json.JSONArray -> parseJsonArray(value, source)?.let { return it }
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) continue
                    val lowerKey = key.lowercase()
                    if (lowerKey in hostKeys) {
                        hostCandidate = trimmed
                    }
                    if (lowerKey in portKeys) {
                        trimmed.toIntOrNull()?.let { portCandidate = it }
                    }
                    ProxyEndpoint.parseFlexible(trimmed, source)?.let { return it }
                }
                is Number -> {
                    val lowerKey = key.lowercase()
                    if (lowerKey in portKeys) {
                        portCandidate = value.toInt()
                    }
                }
            }
        }

        if (!hostCandidate.isNullOrBlank() && portCandidate != null && portCandidate in 1..65535) {
            val candidate = if (hostCandidate.startsWith("tcp://", ignoreCase = true)) {
                hostCandidate
            } else {
                "tcp://${hostCandidate.trim().removePrefix("tcp://")}:${portCandidate}"
            }
            ProxyEndpoint.parse(candidate, source)?.let { return it }
        }

        return null
    }

    private fun parseJsonArray(array: org.json.JSONArray, source: ProxyEndpointSource): ProxyEndpoint? {
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            when (value) {
                is org.json.JSONObject -> parseJsonObject(value, source)?.let { return it }
                is org.json.JSONArray -> parseJsonArray(value, source)?.let { return it }
                is String -> {
                    val parsed = ProxyEndpoint.parseFlexible(value, source)
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }
}
