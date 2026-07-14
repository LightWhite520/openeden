package io.openeden.runtime.pipeline

/** Distinguishes real user turns from proactive heartbeat turns. Heartbeat turns still evolve the
 *  8D vector and evolution_index (§9.3), but MUST NOT update the user-activity silence clock. */
enum class TurnSource { USER, HEARTBEAT }
