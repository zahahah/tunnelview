package com.zahah.tunnelview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.zahah.tunnelview.ui.settings.SettingsScreen
import com.zahah.tunnelview.work.EndpointSyncWorker

class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setTitle(R.string.settings_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
        }

        setContent {
            MaterialTheme {
                SettingsScreen(
                    onSyncNow = { EndpointSyncWorker.enqueueImmediate(applicationContext) },
                    onTestReconnect = {
                        val intent = Intent(this, TunnelService::class.java).setAction(Actions.RECONNECT)
                        if (prefs.persistentNotificationEnabled) {
                            ContextCompat.startForegroundService(this, intent)
                        } else {
                            startService(intent)
                        }
                    },
                    onExit = { finish() }
                )
            }
        }
    }
}
