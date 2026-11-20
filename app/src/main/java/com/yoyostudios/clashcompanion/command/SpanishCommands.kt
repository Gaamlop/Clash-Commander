package com.yoyostudios.clashcompanion.command

import java.text.Normalizer
import java.util.Locale

/** Translate recognized Spanish commands to the existing routing vocabulary. */
object SpanishCommands {
    private val exact = mapOf(
        "para" to "stop", "parar" to "stop", "detente" to "stop",
        "deten" to "stop", "cancelar" to "stop", "cancela" to "stop",
        "basta" to "stop", "no sigas" to "stop",
        "modo manual" to "manual", "desactiva el piloto automatico" to "stop",
        "desactivar piloto automatico" to "stop", "detener piloto automatico" to "stop",
        "piloto automatico" to "autopilot", "modo automatico" to "autopilot",
        "automatico" to "autopilot", "juega solo" to "autopilot",
        "juega por mi" to "autopilot", "activa el piloto automatico" to "autopilot"
    )
    // Longest phrases first; one pass prevents translating generated English again.
    private val phrases = mapOf(
        "gigante esbirro" to "minion giant", "gigante noble" to "royal giant",
        "gigante electrico" to "electro giant", "gigante duende" to "goblin giant",
        "esqueleto gigante" to "giant skeleton", "bola de fuego" to "fireball",
        "bola de nieve" to "giant snowball", "barril de barbaro" to "barbarian barrel",
        "barril de barbaros" to "barbarian barrel", "barril de duendes" to "goblin barrel",
        "barril de esqueletos" to "skeleton barrel", "pandilla de duendes" to "goblin gang",
        "choza de duendes" to "goblin hut", "ejercito de esqueletos" to "skeleton army",
        "espiritu sanador" to "heal spirit", "espiritu de hielo" to "ice spirit",
        "espiritu electrico" to "electro spirit", "espiritu de fuego" to "fire spirit",
        "mago de hielo" to "ice wizard", "mago electrico" to "electro wizard",
        "dragon infernal" to "inferno dragon", "bebe dragon" to "baby dragon",
        "dragon electrico" to "electro dragon", "torre infernal" to "inferno tower",
        "sabueso de lava" to "lava hound", "principe oscuro" to "dark prince",
        "barbaros de elite" to "elite barbarians", "fantasma real" to "royal ghost",
        "carro de canon" to "cannon cart", "carreta de canon" to "cannon cart",
        "ariete de batalla" to "battle ram", "reina arquera" to "archer queen",
        "mi torre izquierda" to "my left tower", "mi torre derecha" to "my right tower",
        "torre enemiga izquierda" to "left tower", "torre enemiga derecha" to "right tower",
        "torre izquierda" to "left tower", "torre derecha" to "right tower",
        "mi rey" to "my king", "su rey" to "his king",
        "en cola" to "queue", "y luego" to "then", "despues" to "then",
        "luego" to "then", "cada vez que" to "whenever", "cuando" to "when",
        "defender" to "defend", "defiende" to "defend", "defensa" to "defend",
        "atacar" to "push", "ataca" to "push", "ataque" to "push",
        "empuja" to "push", "apoya" to "follow up", "apoyar" to "follow up",
        "espera" to "wait", "esperar" to "wait", "izquierda" to "left",
        "derecha" to "right", "centro" to "center", "puente" to "bridge",
        "arriba" to "top", "abajo" to "bottom", "atras" to "back",
        "medio" to "middle", "delante" to "front", "siguiente" to "next",
        "detras" to "behind", "caballero" to "knight", "arqueras" to "archers",
        "gigante" to "giant", "esbirros" to "minions", "flechas" to "arrows",
        "mosquetera" to "musketeer", "montapuercos" to "hog rider",
        "tronco" to "log", "valquiria" to "valkyrie", "canon" to "cannon",
        "esqueletos" to "skeletons", "murcielagos" to "bats", "duendes" to "goblins",
        "principe" to "prince", "globo" to "balloon", "minero" to "miner",
        "veneno" to "poison", "cementerio" to "graveyard", "mago" to "wizard",
        "descarga" to "zap", "cohete" to "rocket", "terremoto" to "earthquake",
        "congelacion" to "freeze", "furia" to "rage", "espejo" to "mirror",
        "bruja" to "witch", "lenador" to "lumberjack", "bandida" to "bandit",
        "horno" to "furnace", "mortero" to "mortar", "pillos" to "rascals",
        "guardias" to "guards", "barbaros" to "barbarians", "lanzarrocas" to "bowler",
        "electrocutadores" to "zappies", "juega" to "play", "lanza" to "play",
        "coloca" to "place", "pon" to "put"
    )
    private val pattern = Regex(phrases.keys.sortedByDescending { it.length }
        .joinToString("|", "\\b(?:", ")\\b") { Regex.escape(it) })
    private val stopOrder = Regex(
        "^(?:para|parar|deten|detente|detener|cancela|cancelar|desactiva|desactivar)" +
            "(?: (?:el )?(?:piloto automatico|modo automatico|autopilot))?(?: ya)?$"
    )

    fun normalize(raw: String): String {
        val text = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\bpor favor\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (stopOrder.matches(text)) return "stop"
        exact[text]?.let { return it }
        return pattern.replace(text) { phrases.getValue(it.value) }
            .replace(Regex("\\b(?:a la|a las|a los|por la|por el|por favor|el|la|los|las|al|en)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
