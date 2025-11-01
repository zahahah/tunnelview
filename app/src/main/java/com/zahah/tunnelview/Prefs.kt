package com.zahah.tunnelview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val appDefaults: AppDefaults = AppDefaultsProvider.defaults(ctx)

    var ntfyFetchEnabled: Boolean
        get() = sp.getBoolean(KEY_NTFY_FETCH_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_NTFY_FETCH_ENABLED, value) }

    var ntfySseUrl: String
        get() {
            val stored = sp.getString("ntfyWsUrl", null)
            val corrected = when {
                stored.isNullOrBlank() -> DEFAULT_NTFY_URL
                stored.equals(LEGACY_DEFAULT_NTFY_URL, ignoreCase = true) -> DEFAULT_NTFY_URL
                else -> stored
            }
            if (corrected != stored) {
                sp.edit { putString("ntfyWsUrl", corrected) }
            }
            return corrected
        }
        set(value) = sp.edit { putString("ntfyWsUrl", value) }

    var ntfyFetchUserOverride: Boolean?
        get() = if (sp.contains(KEY_NTFY_USER_OVERRIDE)) {
            sp.getBoolean(KEY_NTFY_USER_OVERRIDE, true)
        } else {
            null
        }
        set(value) = sp.edit {
            if (value == null) {
                remove(KEY_NTFY_USER_OVERRIDE)
            } else {
                putBoolean(KEY_NTFY_USER_OVERRIDE, value)
            }
        }

    var localPort: Int
        get() {
            val fallback = appDefaults.localPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_LOCAL_PORT
            return sp.getInt("localPort", fallback)
        }
        set(v) = sp.edit { putInt("localPort", v) }

    // REMOTE INTERNAL HOST/PORT (forward destination)
    var remoteHost: String
        get() = sp.getString("remoteHost", appDefaults.remoteInternalHost.ifBlank { "" })!!
        set(value) = sp.edit {
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                remove("remoteHost")
            } else {
                putString("remoteHost", normalized)
            }
        }

    var remotePort: Int
        get() {
            if (sp.contains("remotePort")) {
                return sp.getInt("remotePort", 0)
            }
            return appDefaults.remoteInternalPort.toIntOrNull() ?: 0
        }
        set(value) = sp.edit {
            if (value <= 0) {
                remove("remotePort")
            } else {
                putInt("remotePort", value)
            }
        }

    var httpConnectionEnabled: Boolean
        get() = if (sp.contains(KEY_HTTP_ENABLED)) {
            sp.getBoolean(KEY_HTTP_ENABLED, false)
        } else {
            appDefaults.httpEnabled && httpAddress.isNotBlank()
        }
        set(value) = sp.edit { putBoolean(KEY_HTTP_ENABLED, value) }

    var httpAddress: String
        get() = sp.getString(KEY_HTTP_ADDRESS, appDefaults.httpAddress)?.trim().orEmpty()
        set(value) = sp.edit {
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                remove(KEY_HTTP_ADDRESS)
            } else {
                putString(KEY_HTTP_ADDRESS, normalized)
            }
        }

    var httpHeaderName: String
        get() = sp.getString(KEY_HTTP_HEADER, appDefaults.httpHeader)?.trim().orEmpty()
        set(value) = sp.edit {
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                remove(KEY_HTTP_HEADER)
            } else {
                putString(KEY_HTTP_HEADER, normalized)
            }
        }

    var httpHeaderValue: String
        get() = sp.getString(KEY_HTTP_KEY, appDefaults.httpKey)?.trim().orEmpty()
        set(value) = sp.edit {
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                remove(KEY_HTTP_KEY)
            } else {
                putString(KEY_HTTP_KEY, normalized)
            }
        }

    // SSH SERVER HOST/PORT (e.g., ngrok)
    var sshHost: String?
        get() = sp.getString("sshHost", null)
        set(v) = sp.edit { putString("sshHost", v) }

    var sshPort: Int?
        get() = if (sp.contains("sshPort")) sp.getInt("sshPort", 0) else null
        set(value) = sp.edit {
            if (value == null) remove("sshPort") else putInt("sshPort", value)
        }

    var sshUser: String
        get() = sp.getString("sshUser", appDefaults.sshUser.ifBlank { "" })!!
        set(value) = sp.edit {
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                remove("sshUser")
            } else {
                putString("sshUser", normalized)
            }
        }

    var sshPrivateKeyPem: String?
        get() = sp.getString("sshPrivateKeyPem", null)?.takeIf { it.isNotBlank() }
            ?: appDefaults.sshPrivateKey.takeIf { it.isNotBlank() }
        set(v) = sp.edit {
            val normalized = v?.trim().takeIf { !it.isNullOrEmpty() }
            if (normalized == null) {
                remove("sshPrivateKeyPem")
            } else {
                putString("sshPrivateKeyPem", normalized)
            }
        }

    var usePassword: Boolean
        get() = sp.getBoolean("usePassword", false)
        set(v) = sp.edit { putBoolean("usePassword", v) }

    var sshPassword: String?
        get() = sp.getString("sshPassword", null)
        set(v) = sp.edit { putString("sshPassword", v) }

    /** Last ntfy endpoint in the "tcp://host:port" format */
    var lastEndpoint: String?
        get() = sp.getString("lastEndpoint", null)
        set(v) = sp.edit { putString("lastEndpoint", v) }

    var lastNtfyEndpointAt: Long
        get() = sp.getLong(PrefKeys.LAST_NTFY_ENDPOINT_AT, 0L)
        set(value) = sp.edit { putLong(PrefKeys.LAST_NTFY_ENDPOINT_AT, value) }

    var lastManualSshConfigAt: Long
        get() = sp.getLong(PrefKeys.LAST_MANUAL_SSH_CONFIG_AT, 0L)
        set(value) = sp.edit { putLong(PrefKeys.LAST_MANUAL_SSH_CONFIG_AT, value) }

    var manualSshOverrideFailureStartedAt: Long
        get() = sp.getLong(PrefKeys.MANUAL_SSH_OVERRIDE_FAILURE_STARTED_AT, 0L)
        set(value) = sp.edit { putLong(PrefKeys.MANUAL_SSH_OVERRIDE_FAILURE_STARTED_AT, value) }

    var pendingFallbackSshHost: String?
        get() = sp.getString(PrefKeys.PENDING_FALLBACK_SSH_HOST, null)
        set(value) = sp.edit {
            if (value.isNullOrBlank()) {
                remove(PrefKeys.PENDING_FALLBACK_SSH_HOST)
            } else {
                putString(PrefKeys.PENDING_FALLBACK_SSH_HOST, value)
            }
        }

    var pendingFallbackSshPort: Int?
        get() = if (sp.contains(PrefKeys.PENDING_FALLBACK_SSH_PORT)) {
            sp.getInt(PrefKeys.PENDING_FALLBACK_SSH_PORT, 0).takeIf { it > 0 }
        } else {
            null
        }
        set(value) = sp.edit {
            if (value == null || value <= 0) {
                remove(PrefKeys.PENDING_FALLBACK_SSH_PORT)
            } else {
                putInt(PrefKeys.PENDING_FALLBACK_SSH_PORT, value)
            }
        }

    var pendingNtfyEndpoint: String?
        get() = sp.getString("pendingNtfyEndpoint", null)
        set(value) = sp.edit {
            if (value.isNullOrBlank()) {
                remove("pendingNtfyEndpoint")
            } else {
                putString("pendingNtfyEndpoint", value)
            }
        }

    var lastEndpointSource: String?
        get() = sp.getString("lastEndpointSource", null)
        set(value) = sp.edit {
            if (value.isNullOrBlank()) {
                remove("lastEndpointSource")
            } else {
                putString("lastEndpointSource", value)
            }
        }

    /** When true, ignores ntfy and uses the fixed remoteHost/remotePort */
    var useManualEndpoint: Boolean
        get() = sp.getBoolean("useManualEndpoint", true)
        set(v) = sp.edit { putBoolean("useManualEndpoint", v) }

    var fallbackEndpointUrl: String?
        get() = sp.getString("fallbackEndpointUrl", null)
        set(value) = sp.edit {
            if (value.isNullOrBlank()) {
                remove("fallbackEndpointUrl")
            } else {
                putString("fallbackEndpointUrl", value)
            }
        }

    var cacheLastPage: Boolean
        get() = sp.getBoolean("cacheLastPage", true)
        set(v) = sp.edit { putBoolean("cacheLastPage", v) }

    var cachedHtml: String?
        get() = sp.getString("cachedHtml", null)
        set(v) = sp.edit { putString("cachedHtml", v) }

    var cachedBaseUrl: String?
        get() = sp.getString("cachedBaseUrl", null)
        set(v) = sp.edit { putString("cachedBaseUrl", v) }

    var cachedFullUrl: String?
        get() = sp.getString("cachedFullUrl", null)
        set(v) = sp.edit { putString("cachedFullUrl", v) }

    var cachedRelativePath: String?
        get() = sp.getString("cachedRelativePath", null)
        set(v) = sp.edit { putString("cachedRelativePath", v) }

    var localIpEndpoint: String?
        get() {
            val stored = sp.getString("localIpEndpoint", null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (stored != null) {
                return stored
            }
            val fallbackHost = appDefaults.directHost.trim().takeIf { it.isNotEmpty() } ?: return null
            val fallbackPortRaw = appDefaults.directPort.trim().takeIf { it.isNotEmpty() }
            val fallbackPort = fallbackPortRaw?.toIntOrNull()
            return if (fallbackPort != null) {
                "$fallbackHost:$fallbackPort"
            } else {
                fallbackHost
            }
        }
        set(v) {
            val value = v?.trim()
            sp.edit {
                if (value.isNullOrEmpty()) {
                    remove("localIpEndpoint")
                } else {
                    putString("localIpEndpoint", value)
                }
            }
        }

    fun localIpEndpointRaw(): String? =
        sp.getString("localIpEndpoint", null)

    val ntfyEndpointHistory: List<String>
        get() {
            val raw = sp.getString(PrefKeys.NTFY_HISTORY, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    if (!value.isNullOrBlank()) {
                        list += value
                    }
                }
                list
            } catch (_: Throwable) {
                emptyList()
            }
        }

    fun recordNtfyEndpoint(endpoint: String) {
        val history = ntfyEndpointHistory.toMutableList()
        history.remove(endpoint)
        history.add(0, endpoint)
        while (history.size > 3) {
            history.removeAt(history.lastIndex)
        }
        val payload = JSONArray().apply { history.forEach { put(it) } }.toString()
        sp.edit { putString(PrefKeys.NTFY_HISTORY, payload) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun consumePendingFallbackSshIfEligible(
        graceMs: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val pendingHost = pendingFallbackSshHost?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val pendingPort = pendingFallbackSshPort?.takeIf { it in 1..65535 } ?: run {
            pendingFallbackSshHost = null
            pendingFallbackSshPort = null
            return false
        }
        if (lastManualSshConfigAt > 0L) {
            return false
        }
        sshHost = pendingHost
        sshPort = pendingPort
        pendingFallbackSshHost = null
        pendingFallbackSshPort = null
        lastManualSshConfigAt = 0L
        manualSshOverrideFailureStartedAt = 0L
        return true
    }

    var cachedHtmlPath: String?
        get() = sp.getString("cachedHtmlPath", null)
        set(v) = sp.edit { putString("cachedHtmlPath", v) }

    var cachedHtmlTimestamp: Long
        get() = sp.getLong("cachedHtmlTimestamp", 0L)
        set(v) = sp.edit { putLong("cachedHtmlTimestamp", v) }

    var cachedArchivePath: String?
        get() = sp.getString("cachedArchivePath", null)
        set(v) = sp.edit { putString("cachedArchivePath", v) }

    var persistentNotificationEnabled: Boolean
        get() = sp.getBoolean("persistentNotificationEnabled", false)
        set(value) = sp.edit { putBoolean("persistentNotificationEnabled", value) }

    var connectionDebugLoggingEnabled: Boolean
        get() = sp.getBoolean("connectionDebugLoggingEnabled", false)
        set(value) = sp.edit { putBoolean("connectionDebugLoggingEnabled", value) }

    var forceIpv4: Boolean
        get() = sp.getBoolean(KEY_FORCE_IPV4, false)
        set(value) = sp.edit { putBoolean(KEY_FORCE_IPV4, value) }

    var settingsPassword: String?
        get() = if (sp.contains(PrefKeys.SETTINGS_PASSWORD)) {
            sp.getString(PrefKeys.SETTINGS_PASSWORD, null)
        } else {
            appDefaults.settingsPassword.takeIf { it.isNotBlank() }
        }
        set(value) = sp.edit {
            if (value == null) {
                remove(PrefKeys.SETTINGS_PASSWORD)
            } else {
                putString(PrefKeys.SETTINGS_PASSWORD, value)
            }
        }

    var autoSaveSettings: Boolean
        get() = sp.getBoolean(KEY_AUTO_SAVE_SETTINGS, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_SAVE_SETTINGS, value) }

    var appLanguage: String
        get() = sp.getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE
        set(value) = sp.edit { putString(KEY_APP_LANGUAGE, value) }

    var themeColorId: String
        get() = sp.getString(KEY_THEME_COLOR, DEFAULT_THEME_COLOR) ?: DEFAULT_THEME_COLOR
        set(value) = sp.edit { putString(KEY_THEME_COLOR, value.lowercase(Locale.US)) }

    var themeModeId: String
        get() = sp.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(value) = sp.edit { putString(KEY_THEME_MODE, value.lowercase(Locale.US)) }

    var sshConnectTimeoutSeconds: Int
        get() = sp.getInt(KEY_SSH_CONNECT_TIMEOUT, DEFAULT_TIMEOUT_SECONDS).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        set(value) = sp.edit { putInt(KEY_SSH_CONNECT_TIMEOUT, value.coerceIn(5, 120)) }

    var sshSocketTimeoutSeconds: Int
        get() = sp.getInt(KEY_SSH_SOCKET_TIMEOUT, DEFAULT_TIMEOUT_SECONDS).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        set(value) = sp.edit { putInt(KEY_SSH_SOCKET_TIMEOUT, value.coerceIn(5, 120)) }

    var sshKeepAliveIntervalSeconds: Int
        get() = sp.getInt(KEY_SSH_KEEP_ALIVE, DEFAULT_KEEPALIVE_SECONDS).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        set(value) = sp.edit { putInt(KEY_SSH_KEEP_ALIVE, value.coerceIn(5, 120)) }

    fun sshConnectTimeoutMillis(): Int = sshConnectTimeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1_000

    fun sshSocketTimeoutMillis(): Int = sshSocketTimeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1_000

    companion object {
        const val KEY_NTFY_FETCH_ENABLED = "ntfyFetchEnabled"
        const val KEY_NTFY_USER_OVERRIDE = "ntfyFetchUserOverride"
        private const val KEY_FORCE_IPV4 = "sshForceIpv4"
        private const val KEY_AUTO_SAVE_SETTINGS = "settingsAutoSave"
        private const val KEY_APP_LANGUAGE = "appLanguage"
        const val KEY_THEME_COLOR = "appThemeColor"
        const val KEY_THEME_MODE = "appThemeMode"
        private const val KEY_SSH_CONNECT_TIMEOUT = "sshConnectTimeoutSeconds"
        private const val KEY_SSH_SOCKET_TIMEOUT = "sshSocketTimeoutSeconds"
        private const val KEY_SSH_KEEP_ALIVE = "sshKeepAliveIntervalSeconds"
        private const val KEY_HTTP_ENABLED = "httpConnectionEnabled"
        private const val KEY_HTTP_ADDRESS = "httpAddress"
        private const val KEY_HTTP_HEADER = "httpHeaderName"
        private const val KEY_HTTP_KEY = "httpHeaderValue"
        private const val DEFAULT_TIMEOUT_SECONDS = 20
        private const val DEFAULT_KEEPALIVE_SECONDS = 20
        private const val MIN_TIMEOUT_SECONDS = 15
        private const val MAX_TIMEOUT_SECONDS = 30
        private const val DEFAULT_LOCAL_PORT = 8090
        private const val DEFAULT_NTFY_URL = "https://ntfy.sh/s10e-server-ngrok/sse"
        private const val LEGACY_DEFAULT_NTFY_URL = "https://ntfy.sh/ntfy-update-from-server/sse"
        const val DEFAULT_APP_LANGUAGE = "en"
        const val DEFAULT_THEME_COLOR = "indigo"
        const val DEFAULT_THEME_MODE = "system"
    }
}
