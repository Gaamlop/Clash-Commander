package com.yoyostudios.clashcompanion.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Client pairing for a privately hosted Gemini proxy.
 * Google API keys never belong in the APK or the repository.
 */
object AiConnection {
    private const val PREFS = "ai_connection"
    private const val KEY_ALIAS = "clash_commander_proxy_token_v1"
    @Volatile private var proxyUrl = ""
    @Volatile private var accessToken = ""
    private lateinit var appContext: Context

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        proxyUrl = prefs.getString("proxy_url", "") ?: ""
        accessToken = try {
            decrypt(prefs.getString("sealed_token", "") ?: "")
        } catch (_: Exception) {
            "" // KeyStore entry was invalidated; pair again.
        }
    }

    fun isConfigured(): Boolean = proxyUrl.isNotBlank() && accessToken.isNotBlank()
    fun endpointOrEmpty(): String = proxyUrl

    fun endpoint(): String {
        check(isConfigured()) { "Configura el servidor de IA en Clash Commander" }
        return proxyUrl
    }

    fun bearerToken(): String {
        check(isConfigured()) { "Falta el token del servidor de IA" }
        return accessToken
    }

    fun save(endpointInput: String, tokenInput: String) {
        val candidate = endpointInput.trim().trimEnd('/')
        val url = candidate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Introduce una direccion HTTPS valida")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()
            && url.query == null && url.fragment == null) {
            "El servidor debe usar HTTPS, sin usuario ni parametros"
        }
        val token = tokenInput.trim().ifEmpty { accessToken }
        require(token.length >= 32 && !token.contains(' ')) {
            "El token privado debe tener al menos 32 caracteres sin espacios"
        }
        val sealed = encrypt(token)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("proxy_url", candidate)
            .putString("sealed_token", sealed)
            .apply()
        proxyUrl = candidate
        accessToken = token
    }

    /** Tests pairing without charging a Gemini generation. */
    fun testConnection() {
        val request = Request.Builder()
            .url(endpoint() + "/health")
            .header("Authorization", "Bearer " + bearerToken())
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Servidor IA: HTTP " + response.code)
            }
            val body = response.body?.string() ?: ""
            val health = try {
                com.google.gson.JsonParser.parseString(body).asJsonObject
            } catch (_: Exception) {
                throw IllegalStateException("El servidor no devolvio un estado de conexion valido")
            }
            check(health.get("ok")?.asBoolean == true) { "El servidor no confirma el emparejamiento" }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(cleartext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
