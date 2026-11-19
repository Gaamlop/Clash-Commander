package com.yoyostudios.clashcompanion.command

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutopilotLoopTest {
    @Test fun continuesAfterFirstPlayAndFailedObservationUntilStopped() = runBlocking {
        var cycles=0; var plays=0; var failures=0
        val finished=CompletableDeferred<Unit>()
        val job=launch {
            AutopilotLoop.run(1, cycle={
                cycles++
                when(cycles) {
                    1 -> plays++
                    2 -> throw IllegalStateException("observation unavailable")
                    3 -> Unit // safe wait, no manual action needed
                    4 -> { plays++; finished.complete(Unit) }
                }
            }, onFailure={failures++})
        }
        withTimeout(2_000) { finished.await() }
        job.cancelAndJoin()
        val stopped=cycles
        delay(10)
        assertEquals(stopped,cycles)
        assertEquals(2,plays)
        assertEquals(1,failures)
    }
    @Test fun cancellationDoesNotBecomeAnAutomaticRetry() = runBlocking {
        var cycles=0; var failures=0
        val job=launch {
            AutopilotLoop.run(1,cycle={cycles++;throw CancellationException("stop")},onFailure={failures++})
        }
        withTimeout(2_000) { job.join() }
        assertEquals(1,cycles)
        assertEquals(0,failures)
    }
}
