package com.yoyostudios.clashcompanion.detection

/** Publish completed inference atomically; beginning a scan is not loss of evidence. */
class HandObservationStore {
    class Pending internal constructor(internal val revision: Long)
    private var revision = 0L
    @Volatile var current = HandRecognitionState()
        private set

    @Synchronized fun begin(): Pending = Pending(revision)

    @Synchronized fun complete(pending: Pending, observation: HandRecognitionState): Boolean {
        if (pending.revision != revision) return false
        current = observation
        revision++
        return true
    }

    /** Also fences inference started before capture loss, scanner stop or a match transition. */
    @Synchronized fun replace(observation: HandRecognitionState) {
        current = observation
        revision++
    }
}
