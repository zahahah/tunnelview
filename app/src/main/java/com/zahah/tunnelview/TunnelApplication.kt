package com.zahah.tunnelview

import android.app.Application
import android.content.Context
import com.zahah.tunnelview.ui.theme.AppThemeManager

class TunnelApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.syncApplicationLocales(this)
        val prefs = Prefs(this)
        AppThemeManager.apply(prefs.themeModeId)
    }
}
