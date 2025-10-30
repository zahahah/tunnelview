package com.zahah.tunnelview

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zahah.tunnelview.ssh.TunnelManager
import com.zahah.tunnelview.ui.main.MainCoordinator

class MainActivity : AppCompatActivity() {

    private lateinit var coordinator: MainCoordinator
    private val tunnelManager by lazy { TunnelManager.getInstance(applicationContext) }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (::coordinator.isInitialized) {
                coordinator.onFileChooserResult(result)
            }
        }

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::coordinator.isInitialized) {
                coordinator.onNotificationPermissionResult(granted)
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
        coordinator = MainCoordinator(
            activity = this,
            fileChooserLauncher = fileChooserLauncher,
            requestNotificationPermission = { permission ->
                requestNotifPerm.launch(permission)
            },
            tunnelManager = tunnelManager,
        )
        coordinator.onCreate(savedInstanceState)
        setupBackHandler()
    }

    override fun onStart() {
        super.onStart()
        coordinator.onStart()
    }

    override fun onResume() {
        super.onResume()
        coordinator.onResume()
    }

    override fun onStop() {
        if (::coordinator.isInitialized) {
            coordinator.onStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        coordinator.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        coordinator.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!coordinator.handleBackPressed()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }
}
