package com.zahah.tunnelview.network

import java.net.URI

internal fun parseLocalEndpoint(endpoint: String?): Pair<String?, String?> {
    if (endpoint.isNullOrBlank()) return null to null
    val value = endpoint.trim()
    val explicitScheme = value.contains("://")
    val uri = runCatching { URI(if (explicitScheme) value else "http://$value") }.getOrNull()
        ?: return value to ""
    if (uri.host == null) return value to ""
    val host = buildString {
        if (explicitScheme) append("${uri.scheme}://")
        append(uri.host)
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append("?$it") }
        uri.rawFragment?.let { append("#$it") }
    }
    return host to uri.port.takeIf { it >= 0 }?.toString().orEmpty()
}

internal fun buildLocalEndpoint(host: String, portRaw: String): String? {
    val value = host.trim().takeIf { it.isNotEmpty() } ?: return null
    val port = portRaw.trim().toIntOrNull() ?: return value
    // Insert the port into the authority, not after a path or query.
    val schemeEnd = value.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd)
        .let { if (it >= 0) it else value.length }
    val authority = value.substring(schemeEnd, authorityEnd)
    val withoutPort = authority.replace(Regex(":\\d+$"), "")
    return value.substring(0, schemeEnd) + withoutPort + ":$port" + value.substring(authorityEnd)
}
