package io.openeden.runtime.lifecycle

enum class IncarnationLifecycle {
    ACTIVE,
    CRITICAL,
    TERMINATING,
    TERMINATED,
    ;

    fun transitionTo(next: IncarnationLifecycle): IncarnationLifecycle {
        require(next != this) { "Incarnation is already $this" }
        val legal = when (this) {
            ACTIVE -> next == CRITICAL
            CRITICAL -> next == TERMINATING
            TERMINATING -> next == TERMINATED
            TERMINATED -> false
        }
        check(legal) { "Illegal incarnation lifecycle transition: $this -> $next" }
        return next
    }
}
