package com.yoyostudios.clashcompanion.local

import com.yoyostudios.clashcompanion.command.CommandRouter
import com.yoyostudios.clashcompanion.strategy.DeckProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalStartupTest {
    @Test fun hypnoStartsAndStopsWithoutInitializingAnyGeminiAccountOrPreferences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previous = CommandRouter.deckCards
        try {
            CommandRouter.deckCards = DeckProfiles.hypnoLogBait.cardNames.toList()
            CommandRouter.startAutopilot()
            assertTrue(CommandRouter.isAutopilot)
            assertTrue(LocalArenaModel.enabled)
            CommandRouter.stopAutopilot()
            assertFalse(CommandRouter.isAutopilot)
            assertFalse(LocalArenaModel.enabled)
            runCurrent()
        } finally {
            CommandRouter.stopAutopilot()
            CommandRouter.deckCards = previous
            Dispatchers.resetMain()
        }
    }
}
