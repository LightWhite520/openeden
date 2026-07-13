package io.openeden.runtime

class MutableSessionStateStore(
    private val states: MutableMap<String, SessionState> = mutableMapOf(),
) : SessionStateStore {
    override suspend fun readOrCreate(sessionId: String): SessionState =
        states.getOrPut(sessionId) { SessionStateStore.neutral(sessionId) }

    override suspend fun read(sessionId: String): SessionState = readOrCreate(sessionId)

    override suspend fun write(state: SessionState) {
        states[state.sessionId] = state
    }

    override suspend fun sessionIds(): Set<String> = states.keys.toSet()
}
