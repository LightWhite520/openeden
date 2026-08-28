package io.openeden.memory

import io.openeden.identity.CanonicalSubjectResolver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryVisibilityTest {
    @Test
    fun `private memory is visible only to the bound canonical subject`() {
        assertTrue(MemoryVisibility.PrivateSubject("host").permits("host", "QQ:private"))
        assertFalse(MemoryVisibility.PrivateSubject("host").permits("guest", "QQ:group"))
    }

    @Test
    fun `unbound users retain a stable platform local subject`() {
        val resolver = CanonicalSubjectResolver()

        assertEquals("QQ:user", resolver.resolve("QQ", "user").value)
    }
}
