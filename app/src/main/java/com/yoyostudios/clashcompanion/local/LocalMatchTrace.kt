package com.yoyostudios.clashcompanion.local

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import java.io.File

object LocalMatchTrace {
    private val queue = Channel<String>(128, BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private var trace: BoundedTrace? = null
    @Synchronized fun initialize(context: Context) {
        if (trace != null) return
        trace = BoundedTrace(File(context.filesDir, "local-hypno.jsonl"))
        scope.launch { for (line in queue) runCatching { trace?.append(line) } }
    }
    fun status(message: String) {
        queue.trySend(gson.toJson(mapOf("type" to "status", "uptime_ms" to SystemClock.elapsedRealtime(), "message" to message)))
    }
    fun observation(b: LocalBattle, action: LocalAction, confirmed: Int, abilities: Int, unconfirmed: Int) {
        queue.trySend(gson.toJson(mapOf("type" to "observation", "frame" to b.frameId, "capture_session" to b.captureSession,
            "captured_at_ms" to b.capturedAtMs, "observed_at_ms" to SystemClock.elapsedRealtime(),
            "match_session" to b.matchSession, "age_ms" to SystemClock.elapsedRealtime() - b.capturedAtMs,
            "inference_ms" to b.board.inferenceMs, "hand" to b.hand, "elixir_lower_bound" to b.elixir,
            "units" to b.board.units, "hero_button" to b.board.heroButton,
            "action" to action, "confirmed_cards" to confirmed, "confirmed_abilities" to abilities,
            "unconfirmed" to unconfirmed, "native_self_test" to LocalArenaModel.selfTestPassed,
            "execution_provider" to LocalArenaModel.executionProvider)))
    }
    fun snapshot(): String = trace?.snapshot().orEmpty()

    fun gesture(b: LocalBattle, action: LocalAction, completed: Boolean, startedMs: Long) {
        queue.trySend(gson.toJson(mapOf("type" to "gesture", "frame" to b.frameId,
            "action" to action, "completed" to completed, "duration_ms" to SystemClock.elapsedRealtime() - startedMs,
            "source_age_ms" to SystemClock.elapsedRealtime() - b.capturedAtMs,
            "deployment_confirmed" to false)))
    }
}
