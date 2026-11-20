package com.yoyostudios.clashcompanion.local

data class LocalUnit(val name: String, val enemy: Boolean, val confidence: Float,
                     val x: Float, val y: Float, val width: Float, val height: Float) {
    val feet: Float get() = y + height * .35f
    val base: String get() = name.removeSuffix("-evolution").removeSuffix("-hero")
    val tower: Boolean get() = base in setOf("king-tower", "queen-tower", "cannoneer-tower", "dagger-duchess-tower")
}

data class HeroButton(val x: Float, val y: Float)
data class LocalBoard(val units: List<LocalUnit>, val inferenceMs: Long, val heroButton: HeroButton? = null) {
    val sceneVerified: Boolean get() = units.any { !it.enemy && it.tower && it.confidence >= .55f }
}

data class LocalBattle(val frameId: Long, val captureSession: Long, val matchSession: Long,
                      val capturedAtMs: Long, val hand: Map<Int, String>, val elixir: Int?, val board: LocalBoard,
                      val width: Int = 1080, val height: Int = 2400)

sealed interface LocalAction {
    data class Wait(val reason: String) : LocalAction
    data class Play(val card: String, val slot: Int, val x: Float, val y: Float,
                    val defensive: Boolean, val reason: String) : LocalAction
    data class Ability(val button: HeroButton, val reason: String) : LocalAction
}
