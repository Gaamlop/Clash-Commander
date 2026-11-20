package com.yoyostudios.clashcompanion.local

import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PortraitMatchPolicyTest {
    private val data = javaClass.getResourceAsStream("/hypno-portrait-cases.json")!!.bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }
    private val references = data.getAsJsonArray("references").map {
        val o = it.asJsonObject
        PortraitMatchPolicy.Reference(o.get("name").asString, Base64.getDecoder().decode(o.get("gray").asString))
    }
    @Test fun normalEvolutionAndHeroArtSurviveResizeDimmingAndGrayscale() {
        for (case in data.getAsJsonArray("cases")) {
            val c = case.asJsonObject
            val result = PortraitMatchPolicy.match(Base64.getDecoder().decode(c.get("gray").asString), references, HypnoPolicy.costs.keys)
            val expected = c.get("expected").let { if (it.isJsonNull) null else it.asString }
            assertEquals("${c.get("variant")} ${c.get("transform")}", expected, result)
        }
        assertEquals(HypnoPolicy.costs.keys, references.map { it.name }.toSet())
    }
    @Test fun uniformNoiseAndMalformedDescriptorsAreUnknown() {
        assertNull(PortraitMatchPolicy.match(ByteArray(672) { 100 }, references, HypnoPolicy.costs.keys))
        assertNull(PortraitMatchPolicy.match(ByteArray(2), references, HypnoPolicy.costs.keys))
        val r = java.util.Random(17)
        repeat(10) {
            val noise = ByteArray(672); r.nextBytes(noise)
            assertNull(PortraitMatchPolicy.match(noise, references, HypnoPolicy.costs.keys))
        }
    }
    @Test fun variantsOfSameCardDoNotCompeteAgainstItsOwnConfidenceMargin() {
        val hero = references.last { it.name == "Valkyrie" }
        assertEquals("Valkyrie", PortraitMatchPolicy.match(hero.gray, references + hero, HypnoPolicy.costs.keys))
        assertNull(PortraitMatchPolicy.match(hero.gray, references, HypnoPolicy.costs.keys - "Valkyrie"))
    }
    @Test fun descriptorIgnoresFrameAndBadgeInsteadOfMatchingMagentaNumbers() {
        val w = 120; val h = 140
        val pixels = IntArray(w * h) { i -> val v = (i * 13 % 220) + 15; (255 shl 24) or (v shl 16) or (v shl 8) or v }
        val original = PortraitMatchPolicy.describe(pixels, w, h)
        for (y in 0 until h) for (x in 0 until w) if (x < 14 || x >= 106 || y < 16 || y >= 121)
            pixels[y * w + x] = 0xffff00ff.toInt()
        assertArrayEquals(original, PortraitMatchPolicy.describe(pixels, w, h))
    }
}
