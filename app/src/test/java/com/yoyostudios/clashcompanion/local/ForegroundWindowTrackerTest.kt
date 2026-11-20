package com.yoyostudios.clashcompanion.local

import com.yoyostudios.clashcompanion.accessibility.ForegroundWindowTracker
import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowTrackerTest {
    @Test fun gameOverlayDoesNotReplaceTheUnderlyingForegroundActivity() {
        val t = ForegroundWindowTracker()
        assertNull(t.gameInForeground)
        t.observe("com.supercell.clashroyale", "com.supercell.titan.GameApp", true)
        assertEquals(true, t.gameInForeground)
        t.observe("com.yoyostudios.clashcompanion", "android.widget.LinearLayout", false)
        assertEquals(true, t.gameInForeground)
    }
    @Test fun leavingGameStopsControlAndReturningResumesIt() {
        val t = ForegroundWindowTracker()
        t.observe("com.supercell.clashroyale", "Game", true)
        t.observe("com.android.settings", "SettingsActivity", false)
        assertEquals(false, t.gameInForeground)
        t.observe("com.supercell.clashroyale", "Game", false)
        assertEquals(true, t.gameInForeground)
    }
    @Test fun nativeModalDialogPausesGameControl() {
        val t = ForegroundWindowTracker()
        t.observe("com.supercell.clashroyale", "Game", true)
        t.observe("com.supercell.clashroyale", "android.app.AlertDialog", false)
        assertEquals(false, t.gameInForeground)
        t.observe("com.supercell.clashroyale", "Game", true)
        assertEquals(true, t.gameInForeground)
    }
    @Test fun emptyEventsDoNotInventAnActiveGame() {
        val t = ForegroundWindowTracker()
        t.observe(null, null, true)
        assertNull(t.gameInForeground)
    }
}
