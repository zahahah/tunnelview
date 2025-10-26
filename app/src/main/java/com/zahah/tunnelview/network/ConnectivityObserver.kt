package com.zahah.tunnelview.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Observes system connectivity state and publishes updates through a [StateFlow].
 *
 * Designed so that the SSH tunnel can pause aggressive retries while the device is offline.
 */
class ConnectivityObserver(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    enum class Status { AVAILABLE, UNAVAILABLE, LOSING }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _status = MutableStateFlow(probeInitialStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    fun start() {
        scope.launch {
            flow().collectLatest { status ->
                _status.value = status
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private fun flow() = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Status.AVAILABLE)
            }

            override fun onLost(network: Network) {
                trySend(probeStatus())
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                trySend(Status.LOSING)
            }
        }

        val registered = registerCallback(callback)
        if (!registered) {
            // Fall back to sticky broadcast on very old devices (should not happen with minSdk 26)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    trySend(probeStatus())
                }
            }
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                receiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            )
            awaitClose {
                appContext.unregisterReceiver(receiver)
            }
        } else {
            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }

    private fun probeInitialStatus(): Status = runCatching { probeStatus() }
        .getOrDefault(Status.UNAVAILABLE)

    private fun probeStatus(): Status {
        val cm = connectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return Status.UNAVAILABLE
            val capabilities = cm.getNetworkCapabilities(network) ?: return Status.UNAVAILABLE
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            return if (hasInternet) Status.AVAILABLE else Status.UNAVAILABLE
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return if (info != null && info.isConnectedOrConnecting) {
                if (info.isConnected) Status.AVAILABLE else Status.LOSING
            } else {
                Status.UNAVAILABLE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerCallback(callback: ConnectivityManager.NetworkCallback): Boolean {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    connectivityManager.registerDefaultNetworkCallback(callback)
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    connectivityManager.registerNetworkCallback(request, callback)
                }

                else -> return false
            }
            true
        }.getOrDefault(false)
    }
}
