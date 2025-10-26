package com.zahah.tunnelview

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLocaleManager {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_PORTUGUESE = "pt"

    data class LanguageOption(
        val code: String,
        val titleRes: Int
    )

    fun wrapContext(base: Context): Context {
        val prefs = Prefs(base)
        val language = prefs.appLanguage.ifBlank { Prefs.DEFAULT_APP_LANGUAGE }
        return updateContext(base, language)
    }

    fun syncApplicationLocales(context: Context) {
        val prefs = Prefs(context)
        applyLocales(prefs.appLanguage)
    }

    fun applyLanguage(context: Context, languageCode: String) {
        val normalized = languageCode.ifBlank { Prefs.DEFAULT_APP_LANGUAGE }
        val prefs = Prefs(context)
        if (prefs.appLanguage != normalized) {
            prefs.appLanguage = normalized
        }
        applyLocales(normalized)
    }

    fun availableLanguages(): List<LanguageOption> = listOf(
        LanguageOption(LANGUAGE_ENGLISH, R.string.language_option_english),
        LanguageOption(LANGUAGE_PORTUGUESE, R.string.language_option_portuguese)
    )

    private fun applyLocales(languageCode: String) {
        val tag = toLanguageTag(languageCode)
        val requested = LocaleListCompat.forLanguageTags(tag)
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() != tag) {
            AppCompatDelegate.setApplicationLocales(requested)
        }
    }

    private fun updateContext(base: Context, languageCode: String): Context {
        val locale = toLocale(languageCode)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            configuration.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.setLocale(locale)
        }
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun toLocale(code: String): Locale {
        return when (code.lowercase(Locale.ROOT)) {
            LANGUAGE_PORTUGUESE -> Locale("pt", "BR")
            else -> Locale("en")
        }
    }

    private fun toLanguageTag(code: String): String {
        return when (code.lowercase(Locale.ROOT)) {
            LANGUAGE_PORTUGUESE -> "pt-BR"
            else -> "en"
        }
    }
}
