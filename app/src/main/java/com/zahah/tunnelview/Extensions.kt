package com.zahah.tunnelview

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

fun Context.shortToast(msg: String) =
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun View.shortSnack(msg: String) =
    Snackbar.make(this, msg, Snackbar.LENGTH_SHORT).show()

fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun parseTcpEndpoint(s: String): Pair<String, Int>? {
    if (!s.startsWith("tcp://")) return null
    val hostPort = s.removePrefix("tcp://")
    val parts = hostPort.split(":")
    val port = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val host = parts.firstOrNull().orEmpty()
    if (host.isBlank()) return null
    return host to port
}
