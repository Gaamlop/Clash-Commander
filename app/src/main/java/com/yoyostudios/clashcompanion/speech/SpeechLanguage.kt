package com.yoyostudios.clashcompanion.speech

import android.content.Context
import java.util.Locale

/** The selected command language is independent of the phone's system language. */
object SpeechLanguage {
    private const val PREFS = "speech_settings"
    private const val LANGUAGE = "command_language"

    fun normalize(language: String): String =
        if (language.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_') == "en") {
            "en"
        } else {
            "es"
        }

    fun get(context: Context): String = normalize(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE, "es") ?: "es"
    )

    fun set(context: Context, language: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LANGUAGE, normalize(language)).apply()
    }
}
