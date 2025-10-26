package com.zahah.tunnelview.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.zahah.tunnelview.PrefKeys
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.Timing
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class ProxyRepository private constructor(context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val sharedPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val _endpointFlow = MutableStateFlow(loadActiveEndpoint())
    val endpointFlow: StateFlow<ProxyEndpoint?> = _endpointFlow.asStateFlow()

    private val _statusFlow = MutableSharedFlow<ProxyStatus>(extraBufferCapacity = 8)
    val statusFlow: SharedFlow<ProxyStatus> = _statusFlow.asSharedFlow()

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    fun current(): ProxyEndpoint? = _endpointFlow.value

    fun updateEndpoint(endpoint: ProxyEndpoint) {
        val current = current()
        if (current != null &&
            current.host.equals(endpoint.host, ignoreCase = true) &&
            current.port == endpoint.port &&
            current.source == endpoint.source
        ) {
            Log.d(TAG, "Ignoring endpoint update: already applied ${endpoint.asTcpUrl()}")
            return
        }

        persistEndpoint(endpoint)
        refresh()
        _statusFlow.tryEmit(ProxyStatus.EndpointApplied(endpoint))
    }

    fun updateFromSource(host: String, port: Int, source: ProxyEndpointSource) {
        val endpoint = ProxyEndpoint(host, port, source)
        updateEndpoint(endpoint)
    }

    fun reportError(message: String) {
        _statusFlow.tryEmit(ProxyStatus.Error(message))
    }

    fun reportAuthFailure(message: String) {
        _statusFlow.tryEmit(ProxyStatus.AuthFailure(message))
    }

    fun refresh() {
        _endpointFlow.value = loadActiveEndpoint()
    }

    private fun persistEndpoint(endpoint: ProxyEndpoint) {
        when (endpoint.source) {
            ProxyEndpointSource.NTFY,
            ProxyEndpointSource.FALLBACK -> {
                sharedPrefs.edit()
                    .putString(PrefKeys.LAST_ENDPOINT, endpoint.asTcpUrl())
                    .putString(PrefKeys.LAST_ENDPOINT_SOURCE, endpoint.source.name)
                    .apply()
                if (endpoint.source == ProxyEndpointSource.NTFY || endpoint.source == ProxyEndpointSource.FALLBACK) {
                    prefs.recordNtfyEndpoint(endpoint.asTcpUrl())
                }
                if (endpoint.source == ProxyEndpointSource.NTFY) {
                    prefs.lastNtfyEndpointAt = endpoint.updatedAtMillis
                }
                val manualOverrideActive = prefs.lastManualSshConfigAt > 0L
                if (manualOverrideActive) {
                    prefs.pendingFallbackSshHost = endpoint.host
                    prefs.pendingFallbackSshPort = endpoint.port.takeIf { it in 1..65535 }
                    Log.d(TAG, "Adiado ajuste de SSH fallback devido a override manual recente")
                } else {
                    prefs.pendingFallbackSshHost = null
                    prefs.pendingFallbackSshPort = null
                    prefs.sshHost = endpoint.host
                    prefs.sshPort = endpoint.port
                    if (prefs.lastManualSshConfigAt != 0L) {
                        prefs.lastManualSshConfigAt = 0L
                    }
                    prefs.manualSshOverrideFailureStartedAt = 0L
                }
            }

            ProxyEndpointSource.MANUAL,
            ProxyEndpointSource.DEFAULT -> {
                prefs.remoteHost = endpoint.host
                prefs.remotePort = endpoint.port
                if (endpoint.source == ProxyEndpointSource.MANUAL) {
                    prefs.useManualEndpoint = true
                }
            }
        }
    }

    private fun loadActiveEndpoint(): ProxyEndpoint? {
        prefs.consumePendingFallbackSshIfEligible(Timing.MANUAL_SSH_OVERRIDE_MIN_FAILURE_MS)
        val manualOverrideActive = prefs.lastManualSshConfigAt > 0L
        if (manualOverrideActive) {
            val manualHost = prefs.sshHost?.trim()?.takeIf { it.isNotEmpty() }
            val manualPort = prefs.sshPort?.takeIf { it in 1..65535 }
            if (manualHost != null && manualPort != null) {
                return ProxyEndpoint(manualHost, manualPort, ProxyEndpointSource.MANUAL)
            }
        }
        val stored = sharedPrefs.getString(PrefKeys.LAST_ENDPOINT, null)
        val storedSource = sharedPrefs.getString(PrefKeys.LAST_ENDPOINT_SOURCE, null)
        if (!stored.isNullOrBlank()) {
            val source = storedSource?.let {
                runCatching { ProxyEndpointSource.valueOf(it) }.getOrNull()
            } ?: ProxyEndpointSource.NTFY
            ProxyEndpoint.parse(stored, source)?.let { return it }
        }

        val manualHost = prefs.remoteHost.trim()
        val manualPort = prefs.remotePort
        if (manualHost.isNotEmpty() && manualPort > 0) {
            val source = if (prefs.useManualEndpoint) {
                ProxyEndpointSource.MANUAL
            } else {
                ProxyEndpointSource.DEFAULT
            }
            return ProxyEndpoint(manualHost, manualPort, source)
        }
        return null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        if (
            key == PrefKeys.REMOTE_HOST ||
            key == PrefKeys.REMOTE_PORT ||
            key == PrefKeys.LAST_ENDPOINT ||
            key == PrefKeys.LAST_ENDPOINT_SOURCE ||
            key == PrefKeys.USE_MANUAL_ENDPOINT
        ) {
            refresh()
        }
    }

    companion object {
        private const val TAG = "ProxyRepository"
        private const val PREF_FILE = "prefs"

        @Volatile
        private var instance: ProxyRepository? = null

        fun get(context: Context): ProxyRepository {
            return instance ?: synchronized(this) {
                instance ?: ProxyRepository(context).also { instance = it }
            }
        }
    }
}

sealed class ProxyStatus {
    data class EndpointApplied(val endpoint: ProxyEndpoint) : ProxyStatus()
    data class Error(val message: String) : ProxyStatus()
    data class AuthFailure(val message: String) : ProxyStatus()
}
