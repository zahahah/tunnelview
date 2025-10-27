package com.zahah.tunnelview

object Actions {
    const val START = "com.zahah.tunnelview.action.START"
    const val STOP = "com.zahah.tunnelview.action.STOP"
    const val RECONNECT = "com.zahah.tunnelview.action.RECONNECT"
    const val START_SSE = "com.zahah.tunnelview.action.START_SSE"
    const val STOP_SSE = "com.zahah.tunnelview.action.STOP_SSE"
}

object Events {
    const val BROADCAST = "com.zahah.tunnelview.EVENT"
    const val STATE = "state"
    const val MESSAGE = "message"
    const val EXTRA_STATUS = "status"
}

object Keys {
    const val ENDPOINT = "endpoint" // tcp://host:port
}

object Defaults {
    // default local tunnel port (used by MainActivity)
    val LOCAL_PORT: Int = BuildConfig.DEFAULT_LOCAL_PORT.orEmpty().toIntOrNull() ?: 8090
    // optional ready-to-use URL (in case you prefer using it directly)
    val WEB_URL: String = "http://127.0.0.1:$LOCAL_PORT"
}
