package io.openeden.trace

object TraceTag {
    const val CodebookHeuristicFallback = "codebook=HEURISTIC_FALLBACK"
    const val DiaryQueueOverflow = "diary=QUEUE_OVERFLOW"
    const val VectorWriteSerialized = "vector=WRITE_SERIALIZED"
    const val HeartbeatSource = "source=HEARTBEAT"
    const val ShockStateTransition = "shock=STATE_TRANSITION"
    const val BackgroundDrift = "source=BACKGROUND_DRIFT"
    const val ShockStateDecayed = "shock=DECAYED"
    const val OmegaAccumulated = "omega=ACCUMULATED"
    const val RuntimeTickSessionFailed = "tick=SESSION_FAILED"
}
