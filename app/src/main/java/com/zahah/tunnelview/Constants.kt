package com.zahah.tunnelview

object PrefKeys {
    const val LOCAL_PORT = "localPort"
    const val REMOTE_HOST = "remoteHost"
    const val REMOTE_PORT = "remotePort"
    const val SSH_USER = "sshUser"
    const val USE_PASSWORD = "usePassword"
    const val SSH_PASSWORD = "sshPassword"
    const val LAST_ENDPOINT = "lastEndpoint"
    const val LAST_ENDPOINT_SOURCE = "lastEndpointSource"
    const val NTFY_HISTORY = "ntfyHistory"
    const val USE_MANUAL_ENDPOINT = "useManualEndpoint"
    const val LAST_NTFY_ENDPOINT_AT = "lastNtfyEndpointAt"
    const val LAST_MANUAL_SSH_CONFIG_AT = "lastManualSshConfigAt"
    const val MANUAL_SSH_OVERRIDE_FAILURE_STARTED_AT = "manualSshOverrideFailureStartedAt"
    const val PENDING_FALLBACK_SSH_HOST = "pendingFallbackSshHost"
    const val PENDING_FALLBACK_SSH_PORT = "pendingFallbackSshPort"
}

object Extras {
    const val STATUS = "status"
}

object Timing {
    const val MANUAL_SSH_OVERRIDE_MIN_FAILURE_MS = 20_000L
}
