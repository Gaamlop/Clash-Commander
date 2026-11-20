package com.yoyostudios.clashcompanion.local

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.JsonParser

/** Additional, strict fallback for the exact Hypno deck, including the updated hero illustration. */
object HypnoPortraitMatcher {
    @Volatile private var references = emptyList<PortraitMatchPolicy.Reference>()
    fun initialize(assets: AssetManager) {
        references = runCatching { assets.open("local_hypno/portraits.json").bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("references").map { entry ->
                val obj = entry.asJsonObject
                PortraitMatchPolicy.Reference(obj.get("name").asString, Base64.decode(obj.get("gray").asString, Base64.DEFAULT))
            }
        } }.getOrDefault(emptyList())
    }
    fun match(crop: Bitmap, deck: Set<String>): String? {
        if (deck != HypnoPolicy.costs.keys || references.isEmpty()) return null
        val pixels = IntArray(crop.width * crop.height)
        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        return PortraitMatchPolicy.match(PortraitMatchPolicy.describe(pixels, crop.width, crop.height), references, deck)
    }
}
