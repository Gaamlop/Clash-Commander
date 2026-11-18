package com.yoyostudios.clashcompanion.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saves a user-provided Gemini API key encrypted with an Android Keystore AES key.
 * No API key is checked into source control or embedded into the APK.
 *
 * Note: on-device encryption protects data at rest, but is not equivalent to
 * keeping a secret on a trusted backend. The phone must decrypt the key to call Gemini.
 */
object GeminiKeyStore {
    private const val ALIAS = "clash_commander_gemini_api_key_v1"
    private const val PREFS = "gemini_key_settings"
    private const val ENCRYPTED = "encrypted_key"
    private const val IV = "encrypted_key_iv"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, key: String) {
        require(key.isNotBlank()) { "La clave no puede estar vacía" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(
            prefs.edit()
                .putString(ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        ) { "No se pudo guardar la clave" }
    }

    fun read(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encryptedText = prefs.getString(ENCRYPTED, null) ?: return null
        val ivText = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
        )
        return String(
            cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP)),
            Charsets.UTF_8
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(ENCRYPTED).remove(IV).apply()
    }
}
