package com.yoyostudios.clashcompanion.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.yoyostudios.clashcompanion.util.Coordinates
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor

/**
 * On-device card classifier using YOLOv8n-cls trained on 103 CR card classes.
 *
 * Input:  RGB 224x224 card crop, normalized to [0, 1]
 * Output: Card name (DeckManager key, e.g. "mini-pekka") or null for empty/unknown
 *
 * Accuracy: 98.1% top-1 on validation set
 * Latency:  ~3ms per card on CPU. 5 cards = ~15ms total.
 * Uses YOLOv8n-cls (6.1MB) via PyTorch Mobile Lite for real-time inference.
 */
object CardClassifier {

    private const val TAG = "ClashCompanion"
    private const val MODEL_ASSET = "card_classifier.ptl"
    private const val CLASSES_ASSET = "card_classes.json"
    private const val INPUT_SIZE = 224
    private const val NUM_CHANNELS = 3
    // Reject weak classifications: uncertain slots must stay unknown.
    // Do not choose the 'least wrong' class from the user's deck. The full
    // 103-class model must strongly agree before a tap is allowed.
    private const val CONFIDENCE_THRESHOLD = 0.35f

    // YOLOv8 classify expects raw RGB [0, 1] -- NO ImageNet mean/std normalization

    // Reusable pixel buffer: single getPixels() call vs 50K individual getPixel() JNI calls.
    // Safe because classifyRaw is only called sequentially from the scan coroutine.
    // NOTE: FloatArray is NOT reusable — Tensor.fromBlob holds a reference to the array,
    // so reusing it would corrupt tensor data across sequential classify calls.
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)

    private var module: Module? = null

    /** Class index -> DeckManager card key (e.g. 0 -> "archers", 29 -> "fireball") */
    private var idx2key: Map<Int, String> = emptyMap()

    /** Whether the model is loaded and ready */
    val isReady: Boolean get() = module != null && idx2key.isNotEmpty()

    /** All card keys supported by the on-device model (DeckManager `key` format). */
    fun supportedKeys(): Set<String> = idx2key.values.toSet()

    /** Deck cards that are NOT supported by the on-device model. */
    fun unsupportedDeckCards(deck: List<DeckManager.CardInfo>): List<DeckManager.CardInfo> {
        val supported = supportedKeys()
        val experimental = CardTemplateMatcher.supportedNames()
        return deck.filter { it.key !in supported && it.name !in experimental }
    }

    /**
     * Load the model and class mapping from assets. Call once at startup.
     */
    fun init(context: Context) {
        CardTemplateMatcher.init(context.assets)
        if (module != null) return
        try {
            val startTime = System.currentTimeMillis()

            // Load PyTorch Lite model
            module = LiteModuleLoader.loadModuleFromAsset(context.assets, MODEL_ASSET)

            // Load class index -> card key mapping
            val json = context.assets.open(CLASSES_ASSET).bufferedReader().use { it.readText() }
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            val rawMap: Map<String, String> = Gson().fromJson(json, mapType)
            idx2key = rawMap.mapKeys { it.key.toInt() }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "CARD-ML: Model loaded in ${elapsed}ms (${idx2key.size} classes)")
        } catch (e: Exception) {
            Log.e(TAG, "CARD-ML: Failed to load model: ${e.message}", e)
            module = null
            idx2key = emptyMap()
        }
    }

    /**
     * Classify a single card crop bitmap (unrestricted, all 103 classes).
     * Prefer classifyHand() which restricts to deck cards only.
     */
    fun classify(bitmap: Bitmap): String? {
        val probs = classifyRaw(bitmap) ?: return null
        var maxIdx = 0
        var maxProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > maxProb) {
                maxProb = probs[i]
                maxIdx = i
            }
        }
        if (maxProb < CONFIDENCE_THRESHOLD) return null
        return idx2key[maxIdx]
    }

    /**
     * Classify all 5 card slots (4 hand + 1 next) from a full-screen frame.
     * Only considers cards in the loaded deck (8 candidates, not 103).
     *
     * @param frame Full-screen capture bitmap (1080x2340)
     * @param deckCards List of deck card names (DeckManager format, e.g. "Fireball")
     * @return Map of slotIndex (0-3 hand, 4 next) -> DeckManager card name
     */
    fun classifyHand(frame: Bitmap, deckCards: List<String>): Map<Int, String> {
        if (!isReady && !CardTemplateMatcher.isReady()) return emptyMap()

        val rois = Coordinates.getCardSlotROIs(frame.width, frame.height)
        val nextRoi = Coordinates.getNextCardROI(frame.width, frame.height)

        fun norm(s: String): String =
            s.lowercase().replace(Regex("""[^a-z0-9]"""), "")

        // Build deck-only index mapping: find which output indices correspond to deck cards
        // This restricts classification to ONLY the 8 deck cards instead of all 103
        val deckIndices = mutableListOf<Pair<Int, String>>() // (model output index, DeckManager name)
        for (card in DeckManager.currentDeck) {
            val wantKey = card.key
            val wantKeyNorm = norm(card.key)
            val wantNameNorm = norm(card.name)

            var matchedIdx: Int? = null
            for ((idx, key) in idx2key) {
                val keyNorm = norm(key)
                if (key == wantKey || keyNorm == wantKeyNorm || keyNorm == wantNameNorm) {
                    matchedIdx = idx
                    break
                }
            }
            if (matchedIdx != null) {
                deckIndices.add(matchedIdx to card.name)
            } else {
                Log.w(TAG, "CARD-ML: Deck card not in model classes: '${card.name}' key='${card.key}'")
            }
        }

        if (deckIndices.isEmpty() && !CardTemplateMatcher.isReady()) {
            Log.w(TAG, "CARD-ML: No deck cards mapped to model or templates!")
            return emptyMap()
        }

        val result = mutableMapOf<Int, String>()
        val allowedNames = DeckManager.currentDeck.map { it.name }.toSet()
        val usedNames = mutableSetOf<String>()

        // Classify slots 0-4
        val allRois = rois.mapIndexed { i, roi -> i to roi }.toMutableList()
        allRois.add(4 to nextRoi)

        for ((slot, roi) in allRois) {
            val crop = safeCrop(frame, roi) ?: continue
            try {
                // Owner's video-derived fingerprints recognize new/updated card art.
                // Only main-hand cards: the much smaller next-card artwork is
                // rendered at a different size and needs separate calibration.
                if (slot < 4) {
                    val templateName = CardTemplateMatcher.match(crop, allowedNames)
                    if (templateName != null && templateName !in usedNames) {
                        result[slot] = templateName
                        usedNames.add(templateName)
                        continue
                    }
                }

                // Model fallback only when the best class across ALL 103 classes
                // is also an actual deck card with strong probability.
                // Previously 0.05 among seven deck classes caused false matches.
                val probs = classifyRaw(crop) ?: continue
                if (probs.isEmpty()) continue
                var globalBestIdx = 0
                for (i in 1 until probs.size) {
                    if (probs[i] > probs[globalBestIdx]) globalBestIdx = i
                }
                val deckMatch = deckIndices.firstOrNull {
                    it.first == globalBestIdx && it.second !in usedNames
                }
                if (deckMatch != null && probs[globalBestIdx] >= CONFIDENCE_THRESHOLD) {
                    result[slot] = deckMatch.second
                    usedNames.add(deckMatch.second)
                }
            } finally {
                crop.recycle()
            }
        }

        return result
    }

    // Target mean brightness for normalization (roughly what a normal bright card looks like)
    private const val TARGET_BRIGHTNESS = 0.45f

    /**
     * Run inference and return raw probability array (already softmaxed by model).
     * Includes brightness normalization so dimmed/greyed cards are recognized.
     * Returns null if model not ready or inference fails.
     */
    private fun classifyRaw(bitmap: Bitmap): FloatArray? {
        val mod = module ?: return null

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val numPixels = INPUT_SIZE * INPUT_SIZE

        // Batch read all pixels in a single JNI call (vs 50K individual getPixel calls)
        scaled.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // Must allocate a fresh FloatArray per call — Tensor.fromBlob holds a reference
        // to the array, so reusing a shared buffer corrupts data across sequential calls.
        val floats = FloatArray(NUM_CHANNELS * numPixels)

        // Extract RGB channels into CHW float tensor layout + compute brightness
        var brightnessSum = 0.0f
        for (i in 0 until numPixels) {
            val pixel = pixelBuffer[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            floats[i] = r
            floats[numPixels + i] = g
            floats[2 * numPixels + i] = b
            brightnessSum += (r + g + b) / 3.0f
        }

        // Brightness normalization: scale all values so mean brightness matches target.
        // This makes dimmed/greyed-out cards look like normal bright cards to the model.
        val meanBrightness = brightnessSum / numPixels
        if (meanBrightness > 0.01f) {
            val scale = TARGET_BRIGHTNESS / meanBrightness
            if (scale > 1.1f || scale < 0.9f) { // only normalize if significantly off
                for (i in floats.indices) {
                    floats[i] = (floats[i] * scale).coerceIn(0f, 1f)
                }
            }
        }

        val tensor = Tensor.fromBlob(
            floats,
            longArrayOf(1, NUM_CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val output = mod.forward(IValue.from(tensor)).toTensor()
        return output.dataAsFloatArray
    }

    /**
     * Safely crop a region from a bitmap, handling out-of-bounds and recycled bitmaps.
     */
    private fun safeCrop(frame: Bitmap, roi: Coordinates.CardSlotROI): Bitmap? {
        val fx = roi.x.coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        val fy = roi.y.coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        val fw = roi.w.coerceAtMost((frame.width - fx).coerceAtLeast(1))
        val fh = roi.h.coerceAtMost((frame.height - fy).coerceAtLeast(1))
        if (fw <= 0 || fh <= 0) return null

        return try {
            Bitmap.createBitmap(frame, fx, fy, fw, fh)
        } catch (e: Exception) {
            Log.w(TAG, "CARD-ML: Crop failed: ${e.message}")
            null
        }
    }

    /** Release model resources */
    fun destroy() {
        module?.destroy()
        module = null
        idx2key = emptyMap()
        Log.i(TAG, "CARD-ML: Model released")
    }
}
