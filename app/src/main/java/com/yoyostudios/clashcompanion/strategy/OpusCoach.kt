package com.yoyostudios.clashcompanion.strategy

import android.content.Context
import android.util.Log
import com.yoyostudios.clashcompanion.api.GeminiClient
import com.yoyostudios.clashcompanion.deck.DeckManager
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Deck analysis through the private Gemini relay. Historical class name kept for compatibility. */
object OpusCoach {
    private const val TAG = "ClashCompanion"
    private const val PLAYBOOK_FILE = "playbook.json"
    private data class Entry(val deck: String, val json: String)
    private val stateLock = Any()
    private val requestEpoch = AtomicLong()
    @Volatile private var entry: Entry? = null

    val cachedPlaybook: String?
        get() = entry?.takeIf {
            it.deck == PlaybookPolicy.deckKey(DeckManager.currentDeck.map { card -> card.id })
        }?.json

    fun playbookFor(deck: List<DeckManager.CardInfo>): String? =
        if (deck.size == 8 && PlaybookPolicy.deckKey(deck.map { it.id }) ==
            PlaybookPolicy.deckKey(DeckManager.currentDeck.map { it.id })) cachedPlaybook else null

    /** Every deck assignment invalidates requests, including A -> B -> A transitions. */
    fun onDeckChanged(cards: List<DeckManager.CardInfo>) = synchronized(stateLock) {
        requestEpoch.incrementAndGet()
        val key = PlaybookPolicy.deckKey(cards.map { it.id })
        if (entry?.deck != key) entry = null
    }

    private val SYSTEM_PROMPT = """
You are an expert Clash Royale analyst. Analyze this deck for a real-time voice
command system that will make tactical decisions during live gameplay.

Generate a comprehensive strategic playbook as JSON with these sections:

1. "archetype": Name and one-sentence playstyle summary

2. "win_conditions": Array of primary and secondary win conditions with strategy

3. "defense_table": Object mapping common opponent cards to best counter FROM THIS
   DECK. Each entry: counter_card, placement, timing, elixir_trade, note.
   Cover: Hog Rider, Balloon, Golem, Royal Giant, Giant, Mega Knight, Elite
   Barbarians, Goblin Barrel, Lava Hound, Graveyard, Minion Horde, Skeleton Army,
   Wizard, Witch, Sparky, X-Bow, Mortar, Three Musketeers, P.E.K.K.A, Prince

4. "offense_plays": Recommended offensive sequences with cards, order, placement

5. "card_placement_defaults": For each card in deck, default offensive + defensive zone

6. "synergies": Key card combinations and how they work

7. "never_do": Critical mistakes to avoid with this specific deck

8. "elixir_management": When to push vs defend based on elixir

Use exact card names from the supplied deck. win_conditions must contain objects
with "card" (one exact deck name) and "strategy" (nonempty text). defense_table
entries must contain "counter_card" as one exact deck name. card_placement_defaults
must contain an entry for EACH of the eight exact deck names. Do not invent cards.
Current elixir and evolution availability are unknown; do not assume either.
Respond ONLY with valid JSON. No other text.
    """.trimIndent()

    suspend fun analyzeWithProgress(
        deck: List<DeckManager.CardInfo>,
        context: Context,
        onProgress: (String) -> Unit
    ): String? {
        val cards = deck.toList()
        if (cards.size != 8 || cards.map { it.id }.distinct().size != 8) return null
        val key = PlaybookPolicy.deckKey(cards.map { it.id })
        val epoch = requestEpoch.incrementAndGet()
        val names = cards.map { it.name }.toSet()
        fun current(): Boolean = PlaybookPolicy.requestIsCurrent(
            epoch, requestEpoch.get(), key,
            PlaybookPolicy.deckKey(DeckManager.currentDeck.map { it.id })
        )
        if (!current()) return null
        onProgress("Analizando mazo con Gemini...")
        return try {
            val response = GeminiClient.chat(
                systemInstruction = SYSTEM_PROMPT,
                userMessage = "DECK:\n${Gson().toJson(cards)}\nGenerate a strategic playbook for THIS deck.",
                maxTokens = 8192,
                jsonMode = true
            )
            val result = PlaybookPolicy.validate(GeminiClient.cleanJsonResponse(response), names).toString()
            synchronized(stateLock) {
                if (!current()) return@synchronized null
                entry = Entry(key, result)
                savePlaybook(context, PlaybookPolicy.encode(key, result, names))
                onProgress("Estrategia lista")
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current()) {
                // An older deck's strategy is never used as fallback for this deck.
                loadSavedPlaybook(context)
                onProgress("Estrategia no disponible; revisa la conexion Gemini")
                Log.w(TAG, "Strategy analysis failed (${error.javaClass.simpleName})")
            }
            null
        }
    }

    private fun savePlaybook(context: Context, document: String) {
        try {
            val temporary = File(context.filesDir, "$PLAYBOOK_FILE.tmp")
            temporary.writeText(document)
            check(temporary.renameTo(File(context.filesDir, PLAYBOOK_FILE))) { "No se pudo guardar estrategia" }
        } catch (error: Exception) {
            Log.w(TAG, "Strategy persistence failed (${error.javaClass.simpleName})")
        }
    }

    fun loadSavedPlaybook(context: Context): String? = synchronized(stateLock) {
        val deck = DeckManager.currentDeck.toList()
        val key = PlaybookPolicy.deckKey(deck.map { it.id })
        val loaded = try {
            val file = File(context.filesDir, PLAYBOOK_FILE)
            if (file.isFile) PlaybookPolicy.decode(file.readText(), key, deck.map { it.name }.toSet())
            else null
        } catch (_: Exception) { null }
        if (loaded != null) entry = Entry(key, loaded)
        else if (entry?.deck != key) entry = null
        // If absent, the router retains its general tactical fallback without a fake ready state.
        loaded
    }
}
