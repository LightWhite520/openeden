package io.openeden.runtime.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncarnationLifecycleTest {
    @Test
    fun `lifecycle permits only the ordered critical termination path`() {
        assertEquals(IncarnationLifecycle.CRITICAL, IncarnationLifecycle.ACTIVE.transitionTo(IncarnationLifecycle.CRITICAL))
        assertEquals(IncarnationLifecycle.TERMINATING, IncarnationLifecycle.CRITICAL.transitionTo(IncarnationLifecycle.TERMINATING))
        assertEquals(IncarnationLifecycle.TERMINATED, IncarnationLifecycle.TERMINATING.transitionTo(IncarnationLifecycle.TERMINATED))
    }

    @Test
    fun `lifecycle rejects skipping termination states and resurrection`() {
        assertFailsWith<IllegalStateException> {
            IncarnationLifecycle.ACTIVE.transitionTo(IncarnationLifecycle.TERMINATED)
        }
        assertFailsWith<IllegalStateException> {
            IncarnationLifecycle.TERMINATED.transitionTo(IncarnationLifecycle.ACTIVE)
        }
    }
}
