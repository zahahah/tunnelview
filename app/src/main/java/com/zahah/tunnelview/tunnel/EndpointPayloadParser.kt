package com.zahah.tunnelview.tunnel

import com.zahah.tunnelview.parseTcpEndpoint
import org.json.JSONObject

object EndpointPayloadParser {

    fun parseFlexiblePayload(payload: String): Pair<String, Int>? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            parseJsonObject(json)?.let { return it }
            val nested = sequenceOf(
                json.optString("message"),
                json.optString("data"),
                json.optString("body")
            ).firstOrNull { it.isNotBlank() }
            if (nested != null) {
                return parseFlexiblePayload(nested)
            }
            return null
        }

        if (trimmed.startsWith("tcp://", ignoreCase = true)) {
            return parseTcpEndpoint(trimmed)
        }

        return parseHostPort(trimmed)
    }

    fun parseJsonObject(json: JSONObject): Pair<String, Int>? {
        val host = json.optString("host").trim()
        val port = json.optInt("port", -1)
        if (host.isNotEmpty() && port > 0) {
            return host to port
        }
        return null
    }

    fun parseNtfyEvent(data: String): Pair<String, Int>? {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            parseJsonObject(json)?.let { return it }
            val message = json.optString("message")
            if (message.isNotBlank()) {
                parseFlexiblePayload(message)?.let { return it }
            }
            val body = json.optString("data")
            if (body.isNotBlank()) {
                return parseFlexiblePayload(body)
            }
            val fallback = json.optString("body")
            if (fallback.isNotBlank()) {
                return parseFlexiblePayload(fallback)
            }
            return null
        }
        return parseFlexiblePayload(trimmed)
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            val closing = trimmed.indexOf(']')
            if (closing <= 1 || closing >= trimmed.length - 2) return null
            val host = trimmed.substring(1, closing)
            val remainder = trimmed.substring(closing + 1).trimStart()
            if (!remainder.startsWith(":")) return null
            val port = remainder.removePrefix(":").trim().toIntOrNull() ?: return null
            return host to port
        }

        val colonIndex = trimmed.lastIndexOf(':')
        if (colonIndex <= 0 || colonIndex == trimmed.lastIndex) return null
        val host = trimmed.substring(0, colonIndex).trim()
        val portValue = trimmed.substring(colonIndex + 1).trim()
        if (host.isEmpty()) return null
        val port = portValue.toIntOrNull() ?: return null
        return host to port
    }
}
