package com.yoyostudios.clashcompanion.deck

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeckPresetsTest {
    @Test fun everyPresetResolvesToEightSupportedBaseCards() {
        val assetRoot = File("src/main/assets").takeIf { it.isDirectory } ?: File("app/src/main/assets")
        val cards = JsonParser.parseString(File(assetRoot, "card-data.json").readText()).asJsonArray
            .map { it.asJsonObject }.filter { it.get("is_evolved")?.asBoolean != true }
            .associateBy { it.get("id").asLong }
        val supported = JsonParser.parseString(File(assetRoot, "card_classes.json").readText()).asJsonObject
            .entrySet().map { it.value.asString }.toSet()
        assertEquals(3, DeckPresets.all.size)
        DeckPresets.all.forEach { preset ->
            assertEquals(8, preset.cardIds.distinct().size)
            preset.cardIds.forEach { id ->
                val card = cards[id]
                assertNotNull("Missing catalog entry $id", card)
                assertTrue("Unsupported base card $id", card!!.get("key").asString in supported)
            }
            assertEquals(2, preset.evolutions.size)
            assertEquals(8, preset.sourceSlug.split(',').size)
            assertFalse(preset.sourceSlug.contains("hero"))
        }
    }

    @Test fun matchingRequiresCompleteDeckAndIgnoresOrder() {
        DeckPresets.all.forEach { preset ->
            assertEquals(preset, DeckPresets.matching(preset.cardIds.reversed()))
            assertNull(DeckPresets.matching(preset.cardIds.dropLast(1)))
            assertNull(DeckPresets.matching(List(8) { preset.cardIds.first() }))
        }
    }
}
