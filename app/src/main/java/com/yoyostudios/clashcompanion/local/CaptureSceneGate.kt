package com.yoyostudios.clashcompanion.local

/** Greyed unaffordable cards must not make a verified arena disappear. */
object CaptureSceneGate {
    fun inMatch(localEnabled: Boolean, neuralScene: Boolean?, legacyCardBrightness: Boolean): Boolean =
        if (localEnabled && neuralScene != null) neuralScene else legacyCardBrightness
}
