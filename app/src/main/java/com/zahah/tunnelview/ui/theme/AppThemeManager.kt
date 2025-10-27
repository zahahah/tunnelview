package com.zahah.tunnelview.ui.theme

import androidx.appcompat.app.AppCompatDelegate

object AppThemeManager {
    fun apply(modeId: String) {
        val mode = ThemeModeOption.fromId(modeId)
        val delegateMode = when (mode) {
            ThemeModeOption.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeModeOption.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeModeOption.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != delegateMode) {
            AppCompatDelegate.setDefaultNightMode(delegateMode)
        }
    }
}
