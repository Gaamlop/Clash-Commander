package com.yoyostudios.clashcompanion.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * API client for Google Gemini 3 Flash.
 * Used for:
 *   - Vision/multimodal queries (images + text)
 *   - M8: Real-time tactical decisions (text-only Smart Path)
 *
 * Separate from AnthropicClient which handles Opus only.
 * All calls run on Dispatchers.IO.
 */
object GeminiClient {

    private const val TAG = "ClashCompanion"
    private val providerBackoff = ProviderBackoff()
    val cooldownRemainingMs: Long get() = providerBackoff.remainingMs()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Text-only chat with Gemini Flash.
     * For M8 Smart Path: strategic decisions using Opus playbook.
     *
     * @param systemInstruction System instruction text
     * @param userMessage User message text
     * @param maxTokens Maximum output tokens
     * @param jsonMode If true, sets responseMimeType to application/json
     * @return Response text from Gemini
     */
    suspend fun chat(
        systemInstruction: String,
        userMessage: String,
        maxTokens: Int = 1024,
        jsonMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        // Build parts array with text only
        val partsArray = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", userMessage) })
        }

        val body = buildRequestBody(systemInstruction, partsArray, maxTokens, jsonMode)
        return@withContext executeRequest(body)
    }

    /**
     * Vision/multimodal chat with Gemini Flash.
     * For M6: card identification from hand screenshot crops.
     *
     * @param systemInstruction System instruction text
     * @param userText Text prompt accompanying the images
     * @param images List of base64-encoded JPEG strings
     * @param maxTokens Maximum output tokens
     * @param jsonMode If true, sets responseMimeType to application/json
     * @return Response text from Gemini
     */
    suspend fun chatWithImages(
        systemInstruction: String,
        userText: String,
        images: List<String>,
        maxTokens: Int = 1024,
        jsonMode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        // Build parts array: images first, then text
        val partsArray = JsonArray()
        for (img in images) {
            partsArray.add(JsonObject().apply {
                add("inlineData", JsonObject().apply {
                    addProperty("mimeType", "image/jpeg")
                    addProperty("data", img)
                })
            })
        }
        partsArray.add(JsonObject().apply { addProperty("text", userText) })

        val body = buildRequestBody(systemInstruction, partsArray, maxTokens, jsonMode)
        return@withContext executeRequest(body)
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Convert a Bitmap to a base64-encoded JPEG string.
     * @param bitmap Source bitmap (not recycled by this method)
     * @param quality JPEG compression quality (0-100)
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Clean Gemini response text by stripping markdown fences and preamble.
     * Gemini 3 Flash (thinking model) often returns:
     *   "Here is the JSON requested:\n```json\n[...]\n```"
     * This extracts just the JSON content.
     */
    fun cleanJsonResponse(raw: String): String {
        var text = raw.trim()

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        val fencePattern = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val fenceMatch = fencePattern.find(text)
        if (fenceMatch != null) {
            text = fenceMatch.groupValues[1].trim()
        }

        // If still not starting with [ or {, try to find JSON array/object in the text
        if (!text.startsWith("[") && !text.startsWith("{")) {
            val jsonStart = text.indexOfFirst { it == '[' || it == '{' }
            if (jsonStart >= 0) {
                text = text.substring(jsonStart)
                // Find matching close
                val openChar = text[0]
                val closeChar = if (openChar == '[') ']' else '}'
                val closeIdx = text.lastIndexOf(closeChar)
                if (closeIdx > 0) {
                    text = text.substring(0, closeIdx + 1)
                }
            }
        }

        return text
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Build the Gemini API request body JSON.
     */
    private fun buildRequestBody(
        systemInstruction: String,
        partsArray: JsonArray,
        maxTokens: Int,
        jsonMode: Boolean
    ): JsonObject {
        return JsonObject().apply {
            // contents: [{ parts: [...] }]
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", partsArray)
                })
            })

            // systemInstruction: { parts: [{ text: "..." }] }
            if (systemInstruction.isNotBlank()) {
                add("systemInstruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", systemInstruction)
                        })
                    })
                })
            }

            // generationConfig
            // thinkingLevel "minimal" disables thinking for fast, direct responses.
            // Without this, Flash burns tokens on internal reasoning and wraps
            // output in markdown fences.
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.1)
                addProperty("maxOutputTokens", maxTokens)
                add("thinkingConfig", JsonObject().apply {
                    addProperty("thinkingLevel", "minimal")
                })
                if (jsonMode) {
                    addProperty("responseMimeType", "application/json")
                }
            })
        }
    }

    /**
     * Execute the HTTP request and parse the response.
     * Extracts candidates[0].content.parts[0].text from Gemini response.
     */
    private suspend fun executeRequest(body: JsonObject): String {
        providerBackoff.checkAllowed()
        val url = AiConnection.endpoint() + "/v1/gemini"
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + AiConnection.bearerToken())
            .addHeader("content-type", "application/json")
            .post(requestBody)
            .build()

        Log.i(TAG, "GEMINI: Calling private Gemini proxy")
        val startTime = System.currentTimeMillis()

        val responseBody = try {
            client.newCall(request).awaitResponseBody().also { providerBackoff.succeeded() }
        } catch (error: GeminiHttpException) {
            if (error.statusCode == 429) providerBackoff.rateLimited(error.retryAfterMs)
            throw error
        }
        val elapsed = System.currentTimeMillis() - startTime

        Log.i(TAG, "GEMINI: Response in ${elapsed}ms (${responseBody.length} chars)")

        // Parse: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
        try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            // Check for prompt feedback / safety blocks
            val promptFeedback = json.getAsJsonObject("promptFeedback")
            if (promptFeedback != null) {
                val blockReason = promptFeedback.get("blockReason")?.asString
                if (blockReason != null) {
                    Log.e(TAG, "GEMINI: Blocked by safety filter: $blockReason")
                    throw RuntimeException("Gemini blocked: $blockReason")
                }
            }

            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                throw RuntimeException("No candidates in Gemini response")
            }

            val candidate = candidates[0].asJsonObject

            // Check for finish reason issues
            val finishReason = candidate.get("finishReason")?.asString
            if (finishReason != null && finishReason != "STOP") {
                Log.w(TAG, "GEMINI: finishReason=$finishReason")
            }

            val content = candidate.getAsJsonObject("content")
            if (content == null) {
                throw RuntimeException("No content in Gemini candidate")
            }

            val parts = content.getAsJsonArray("parts")
            if (parts != null && parts.size() > 0) {
                val text = parts.mapNotNull { part ->
                    val value = part.asJsonObject
                    if (value.get("thought")?.asBoolean == true) null
                    else value.get("text")?.asString
                }.joinToString("")
                check(text.isNotBlank()) { "Empty text in Gemini response" }
                Log.i(TAG, "GEMINI: Extracted text (${text.length} chars)")
                return text
            }

            throw RuntimeException("No parts in Gemini content")
        } catch (e: Exception) {
            Log.e(TAG, "GEMINI: Invalid response (${e.javaClass.simpleName})")
            throw RuntimeException("No se pudo interpretar la respuesta de Gemini")
        }
    }

}
