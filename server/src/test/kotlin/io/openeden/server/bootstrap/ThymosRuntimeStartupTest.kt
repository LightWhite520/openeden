package io.openeden.server.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals

class ThymosRuntimeStartupTest {
    @Test
    fun `Thymos runtime is prepared before any DJL models are created`() {
        val events = mutableListOf<String>()

        val result = withPreparedThymosRuntime(
            prepare = { events += "prepare" },
        ) {
            events += "models"
            42
        }

        assertEquals(42, result)
        assertEquals(listOf("prepare", "models"), events)
    }
}
