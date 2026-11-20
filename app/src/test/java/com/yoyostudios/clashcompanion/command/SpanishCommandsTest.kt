package com.yoyostudios.clashcompanion.command

import org.junit.Assert.*
import org.junit.Test

class SpanishCommandsTest {
    private val deck = listOf("Knight", "Archers", "Minions", "Arrows", "Fireball", "Giant", "Musketeer", "Mini P.E.K.K.A")

    @Test fun tacticalOrdersKeepTheirDirection() {
        assertEquals("defend right", CommandParser.cleanTranscript("¡Defiende a la derecha!"))
        assertEquals("push left", CommandParser.cleanTranscript("Ataca por la izquierda"))
        assertEquals("follow up", CommandParser.cleanTranscript("Apoya"))
        assertEquals("wait", CommandParser.cleanTranscript("Espera"))
    }

    @Test fun spanishStopAndAutopilotAreUnambiguous() {
        listOf("Detente", "Para", "Cancelar", "Desactiva el piloto automático", "Para, por favor",
            "Detén el piloto automático", "Para el piloto automático", "Por favor, detente ya").forEach {
            assertEquals("stop", CommandParser.cleanTranscript(it))
        }
        assertNotEquals("stop", CommandParser.cleanTranscript("Para defender"))
        listOf("Piloto automático", "Juega solo", "Juega por mí").forEach {
            assertEquals("autopilot", CommandParser.cleanTranscript(it))
        }
    }

    @Test fun directAndChainedCommandsReuseExistingParser() {
        val command = CommandParser.parse("Caballero a la izquierda", deck)
        assertEquals(CommandParser.CommandTier.FAST, command.tier)
        assertEquals("Knight", command.card)
        assertEquals("left", command.zone)
        assertEquals("knight left then musketeer right",
            CommandParser.cleanTranscript("Caballero izquierda y luego mosquetera derecha"))
    }

    @Test fun englishCommandsAndCardNamesStillWork() {
        assertEquals("knight left then archers right", CommandParser.cleanTranscript("knight left then archers right"))
        assertEquals("Mini P.E.K.K.A", CommandParser.parse("mini pekka right", deck).card)
        assertEquals("mini pekka right", CommandParser.cleanTranscript("Mini P.E.K.K.A right"))
        assertEquals("minion giant right", SpanishCommands.normalize("Gigante Esbirro derecha"))
    }
}
