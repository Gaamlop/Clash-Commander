package com.yoyostudios.clashcompanion.command

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A failed observation must not silently terminate autonomous observation. */
object AutopilotLoop {
    suspend fun run(intervalMs: Long, cycle: suspend () -> Unit, onFailure: (Exception) -> Unit) {
        require(intervalMs > 0)
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            try { cycle() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { onFailure(failure) }
        }
    }
}
