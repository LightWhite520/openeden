package io.openeden.server.bootstrap

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeShutdownCoordinatorTest {
    @Test
    fun `stopping cancels runtime child and stopped closes after parent join`() = runTest {
        val events = mutableListOf<String>()
        val applicationJob = Job()
        val runtimeJob = SupervisorJob(applicationJob)
        val runtimeScope = CoroutineScope(coroutineContext + runtimeJob)
        val firstJob = runtimeScope.launch {
            try {
                awaitCancellation()
            } finally {
                events += "first joined"
            }
        }
        val secondJob = runtimeScope.launch {
            try {
                awaitCancellation()
            } finally {
                events += "second joined"
            }
        }
        val coordinator = RuntimeShutdownCoordinator(
            runtimeJob = runtimeJob,
            closers = listOf(
                { events += "first closed" },
                { events += "second closed" },
            ),
        )

        runCurrent()
        coordinator.stopping()
        assertTrue(events.isEmpty())

        applicationJob.cancelAndJoin()
        val failure = coordinator.stopped()

        assertEquals(null, failure)
        assertEquals(
            listOf("first joined", "second joined", "first closed", "second closed"),
            events,
        )
        assertTrue(firstJob.isCompleted)
        assertTrue(secondJob.isCompleted)
    }

    @Test
    fun `stopped aggregates close failures and continues closing`() = runTest {
        val events = mutableListOf<String>()
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val coordinator = RuntimeShutdownCoordinator(
            runtimeJob = Job(),
            closers = listOf(
                {
                    events += "first"
                    throw firstFailure
                },
                {
                    events += "second"
                    throw secondFailure
                },
                { events += "third" },
            ),
        )

        val failure = coordinator.stopped()

        val aggregated = assertNotNull(failure)
        assertSame(firstFailure, aggregated)
        assertEquals(listOf(secondFailure), aggregated.suppressed.toList())
        assertEquals(listOf("first", "second", "third"), events)
        assertFalse(events.isEmpty())
    }

    @Test
    fun `stopped tolerates the same failure instance and still closes later resources`() {
        val events = mutableListOf<String>()
        val sharedFailure = IllegalStateException("shared")
        val coordinator = RuntimeShutdownCoordinator(
            runtimeJob = Job(),
            closers = listOf(
                {
                    events += "first"
                    throw sharedFailure
                },
                {
                    events += "second"
                    throw sharedFailure
                },
                { events += "third" },
            ),
        )

        val failure = coordinator.stopped()

        assertSame(sharedFailure, failure)
        assertTrue(sharedFailure.suppressed.isEmpty())
        assertEquals(listOf("first", "second", "third"), events)
    }
}
