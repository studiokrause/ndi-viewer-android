package com.lekozaur.ndiviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.Locale

object LocaleHelper {
    private const val PREFS = "ndiviewer_prefs"
    private const val KEY_LANG = "lang"
    val SUPPORTED = listOf("pl", "en", "de", "es", "it", "fr")
    val NAMES = mapOf(
        "pl" to "PL",
        "en" to "EN",
        "de" to "DE",
        "es" to "ES",
        "it" to "IT",
        "fr" to "FR",
    )

    fun getLang(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_LANG, Locale.getDefault().language)?.takeIf { it in SUPPORTED } ?: "pl"
    }

    fun setLang(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, lang).apply()
    }

    fun wrapContext(ctx: Context): Context {
        val lang = getLang(ctx)
        return updateResources(ctx, lang)
    }

    fun updateResources(ctx: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val res = ctx.resources
        val config = res.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return ctx.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return ctx
        }
    }
}
