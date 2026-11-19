package com.yoyostudios.clashcompanion.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechLanguageTest {
    @Test fun selectsSpanishForSpanishLocalesAndUnsupportedLanguages() {
        listOf("es", "es-MX", "ES_es", "fr", "").forEach {
            assertEquals("es", SpeechLanguage.normalize(it))
        }
    }

    @Test fun preservesEnglishRegardlessOfPhoneLocaleFormatting() {
        listOf("en", "en-US", "EN_gb", " en ").forEach {
            assertEquals("en", SpeechLanguage.normalize(it))
        }
    }
}
