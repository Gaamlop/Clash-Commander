package com.yoyostudios.clashcompanion.detection

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import kotlin.math.sqrt

/**
 * Experimental, owner-approved visual recognition fallback.
 *
 * Uses compact card-art fingerprints extracted from the user's own practice replay.
 * These are NOT a retrained neural network and will not generalize to every skin,
 * evolution, arena theme or device. A high absolute-confidence threshold prevents
 * an unseen card from being guessed merely because it is the nearest template.
 * Only fingerprints for cards in the actual eight-card deck may match.
 */
object CardTemplateMatcher {
    private const val TAG = "ClashCompanion"
    private const val FILE = "hand_templates.json"
    private const val SIZE = 12
    private const val MAX_RMSE = 0.145
    private const val MIN_MARGIN = 0.065

    private data class Template(val name: String, val rgb: ByteArray)

    @Volatile
    private var templates: List<Template> = emptyList()
    private var initialized = false

    fun init(assets: AssetManager) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val text = assets.open(FILE).bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(text).asJsonObject
                require(root.get("size").asInt == SIZE)
                val parsed = root.getAsJsonArray("templates").mapNotNull { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("name")?.asString ?: return@mapNotNull null
                    val rgb = Base64.decode(obj.get("rgb").asString, Base64.DEFAULT)
                    if (rgb.size != SIZE * SIZE * 3) null else Template(name, rgb)
                }
                templates = parsed
                Log.i(TAG, "CARD-VISION: Loaded ${parsed.size} experimental fingerprints")
            } catch (e: Exception) {
                Log.w(TAG, "CARD-VISION: Failed to load fingerprints: ${e.message}")
                templates = emptyList()
            }
            initialized = true
        }
    }

    fun supportedNames(): Set<String> = templates.map { it.name }.toSet()
    fun isReady(): Boolean = templates.isNotEmpty()

    /**
     * Return a high-confidence match, or null if none is reliable.
     * Do NOT guess when a card is unknown or has different artwork.
     */
    fun match(cardCrop: Bitmap, allowedCards: Set<String>): String? {
        val candidates = templates.filter { it.name in allowedCards }
        if (candidates.isEmpty()) return null

        val scaled = Bitmap.createScaledBitmap(cardCrop, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        try {
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        } finally {
            if (scaled !== cardCrop) scaled.recycle()
        }

        var bestName: String? = null
        var best = Double.POSITIVE_INFINITY
        var second = Double.POSITIVE_INFINITY

        for (candidate in candidates) {
            var squared = 0.0
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val offset = i * 3
                val redDiff = ((pixel shr 16) and 255) - (candidate.rgb[offset].toInt() and 255)
                val greenDiff = ((pixel shr 8) and 255) - (candidate.rgb[offset + 1].toInt() and 255)
                val blueDiff = (pixel and 255) - (candidate.rgb[offset + 2].toInt() and 255)
                squared += (redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff).toDouble()
            }
            val distance = sqrt(squared / (pixels.size * 3)) / 255.0
            if (distance < best) {
                second = best
                best = distance
                bestName = candidate.name
            } else if (distance < second) {
                second = distance
            }
        }

        return if (best <= MAX_RMSE && second - best >= MIN_MARGIN) bestName else null
    }
}
