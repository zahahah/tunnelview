package com.zahah.tunnelview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zahah.tunnelview.data.ProxyRepository
import com.zahah.tunnelview.ssh.TunnelManager
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.work.EndpointSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TunnelService : Service() {

    companion object {
        private const val TAG = "TunnelService"
        private const val NOTIF_CHANNEL_ID = "tunnel_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        private var serviceRunning: Boolean = false

        fun isRunning(): Boolean = serviceRunning
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private val proxyRepository: ProxyRepository by lazy { ProxyRepository.get(this) }
    private val credentialsStore: CredentialsStore by lazy { CredentialsStore.getInstance(this) }
    private val tunnelManager: TunnelManager by lazy { TunnelManager.getInstance(applicationContext) }
    private var stateJob: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        serviceRunning = true
        startStateObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: Actions.START) {
            Actions.START -> handleStart()
            Actions.RECONNECT -> handleReconnect()
            Actions.STOP -> stopSelf()
            else -> handleStart()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tunnelManager.stop() }
        }
        scope.cancel()
        stopForegroundCompat()
        cancelNotification()
        super.onDestroy()
        serviceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStart() {
        tunnelManager.ensureStarted()
        startStateObservers()
        ensureForegroundState(getString(R.string.tunnel_status_connecting))
        sendEvent("CONNECTING")
    }

    private fun handleReconnect() {
        tunnelManager.ensureStarted()
        tunnelManager.forceReconnect("service_reconnect")
        ensureForegroundState(getString(R.string.tunnel_status_reconnecting))
        sendEvent("CONNECTING")
    }

    private fun startStateObservers() {
        if (stateJob?.isActive == true) return
        stateJob = scope.launch {
            tunnelManager.state.collect { state ->
                when (state) {
                    is TunnelManager.State.Connecting -> {
                        updateNotif(getString(R.string.tunnel_status_connecting))
                        sendEvent("CONNECTING")
                    }

                    is TunnelManager.State.Connected -> {
                        onTunnelConnected()
                    }

                    is TunnelManager.State.LocalBypass -> {
                        updateNotif(getString(R.string.tunnel_status_local_active))
                        sendEvent("LOCAL")
                    }

                    is TunnelManager.State.WaitingForEndpoint -> {
                        onWaitingForEndpoint()
                    }

                    is TunnelManager.State.WaitingForNetwork -> {
                        updateNotif(getString(R.string.tunnel_waiting_network))
                        sendEvent("WAITING_NETWORK")
                    }

                    is TunnelManager.State.Failed -> {
                        onTunnelFailure(state)
                    }

                    TunnelManager.State.Idle -> {
                        sendEvent("DISCONNECTED")
                        stopForegroundCompat()
                        cancelNotification()
                    }

                    TunnelManager.State.Stopping -> Unit
                }
            }
        }
    }

    private fun onTunnelConnected() {
        val text = getString(R.string.tunnel_status_connected, prefs.localPort)
        updateNotif(text)
        if (prefs.ntfyFetchUserOverride != false) {
            prefs.ntfyFetchEnabled = false
        }
        sendEvent("CONNECTED")
    }

    private fun onWaitingForEndpoint() {
        if (prefs.ntfyFetchUserOverride != false) {
            prefs.ntfyFetchEnabled = true
        }
        ensureSseServiceStarted()
        scheduleEndpointRefresh()
        updateNotif(getString(R.string.waiting_dynamic_endpoint))
        sendEvent("WAITING_ENDPOINT")
    }

    private fun onTunnelFailure(state: TunnelManager.State.Failed) {
        if (prefs.ntfyFetchUserOverride != false) {
            prefs.ntfyFetchEnabled = true
        }
        ensureSseServiceStarted()
        scheduleEndpointRefresh()
        val message = state.message.ifBlank { getString(R.string.tunnel_status_error_generic) }
        updateNotif(getString(R.string.tunnel_status_error, message))
        sendEvent("ERROR: $message")
    }

    private fun ensureForegroundState(text: String) {
        val notification = buildNotification(text)
        if (!isForeground) {
            startForeground(NOTIF_ID, notification)
            isForeground = true
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification)
        }
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
        if (!isForeground) {
            startForeground(NOTIF_ID, buildNotification(text))
            isForeground = true
        }
    }

    private fun cancelNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    private fun stopForegroundCompat() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(!prefs.persistentNotificationEnabled)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.channel_tunnel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_tunnel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun ensureSseServiceStarted() {
        if (prefs.ntfyFetchUserOverride == false) return
        val topic = credentialsStore.ntfyTopic()?.trim().orEmpty()
        if (topic.isEmpty()) return
        val intent = Intent(applicationContext, NtfySseService::class.java).apply {
            action = Actions.START_SSE
            putExtra(Keys.ENDPOINT, topic)
        }
        try {
            ContextCompat.startForegroundService(applicationContext, intent)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Falha ao iniciar NtfySseService: ${error.message}")
        }
    }

    private fun scheduleEndpointRefresh() {
        EndpointSyncWorker.enqueueImmediate(applicationContext)
    }

    private fun sendEvent(msg: String) {
        val intent = Intent(Events.BROADCAST).apply {
            putExtra(Events.EXTRA_STATUS, msg)
            putExtra(Events.MESSAGE, msg)
        }
        sendBroadcast(intent)
    }
}
