package io.openeden.runtime.lifecycle

interface IncarnationTerminationStore : IncarnationLifecycleStore {
    suspend fun archiveAndPurge(reason: TerminationReason)
}
