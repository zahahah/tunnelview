package com.zahah.tunnelview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zahah.tunnelview.data.ProxyRepository
import com.zahah.tunnelview.network.NtfyStatus
import com.zahah.tunnelview.network.NtfySubscriber
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NtfySseService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private val proxyRepository by lazy { ProxyRepository.get(this) }
    private val credentialsStore by lazy { CredentialsStore.getInstance(this) }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val sharedPrefs: SharedPreferences by lazy {
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        HttpClient.shared(this)
            .newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val subscriber by lazy { NtfySubscriber(httpClient, proxyRepository, serviceScope) }
    private var statusJob: Job? = null
    private var currentStatus: String = ""
    private var evaluateJob: Job? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_NTFY_FETCH_ENABLED || key == Prefs.KEY_NTFY_USER_OVERRIDE) {
                evaluateState()
            }
        }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        observeStatusUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            Actions.STOP_SSE -> {
                stopSubscriber()
                stopForegroundCompat()
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                val topicOverride = intent?.getStringExtra(Keys.ENDPOINT)
                ensureForeground(
                    if (currentStatus.isBlank()) getString(R.string.ntfy_status_listening) else currentStatus
                )
                evaluateState(topicOverride)
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopSubscriber()
        statusJob?.cancel()
        serviceScope.cancel()
        evaluateJob?.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluateState(topicOverride: String? = null) {
        evaluateJob?.cancel()
        evaluateJob = serviceScope.launch {
            evaluateStateInternal(topicOverride)
        }
    }

    private suspend fun evaluateStateInternal(topicOverride: String?) {
        if (prefs.ntfyFetchUserOverride == false) {
            stopSubscriber()
            updateNotification(getString(R.string.ntfy_status_disabled))
            return
        }
        if (!prefs.ntfyFetchEnabled) {
            stopSubscriber()
            updateNotification(getString(R.string.ntfy_status_paused))
            return
        }
        startSubscriber(topicOverride)
    }

    private suspend fun startSubscriber(topicOverride: String?) {
        val topic = topicOverride?.takeIf { it.isNotBlank() }
            ?: credentialsStore.ntfyTopic()?.trim()
        if (topic.isNullOrEmpty()) {
            stopSubscriber()
            updateNotification(getString(R.string.ntfy_status_missing_topic))
            return
        }
        subscriber.start(topic)
    }

    private fun stopSubscriber() {
        subscriber.stop()
    }

    private fun observeStatusUpdates() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            subscriber.statusFlow.collect { status ->
                val message = when (status) {
                    is NtfyStatus.Connecting -> getString(R.string.ntfy_status_connecting)
                    NtfyStatus.Connected -> getString(R.string.ntfy_status_connected)
                    is NtfyStatus.Disconnected -> getString(R.string.ntfy_status_disconnected)
                    NtfyStatus.Retrying -> getString(R.string.ntfy_status_retrying)
                    is NtfyStatus.IgnoredPayload -> getString(R.string.ntfy_status_ignored)
                    is NtfyStatus.Error -> status.message
                }
                currentStatus = message
                updateNotification(message)
            }
        }
    }

    private fun ensureForeground(text: String) {
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.ntfy_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ntfy_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "ntfy_subscriber_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, NtfySseService::class.java).apply {
                action = Actions.START_SSE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NtfySseService::class.java).apply {
                    action = Actions.STOP_SSE
                }
            )
        }
    }
}
