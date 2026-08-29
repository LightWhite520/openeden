package io.openeden.compatibility

import io.openeden.runtime.incarnation.IncarnationMutexRegistry
import io.openeden.runtime.incarnation.IncarnationStateStore
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.session.SessionMutexRegistry
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.state.BackgroundDynamicsReducer
import io.openeden.runtime.state.VectorDeltaReducer
import io.openeden.runtime.state.VectorWriteService
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VectorWriteServiceBinaryCompatibilityTest {
    @Test
    fun `keeps pre-factory direct and synthetic constructor descriptors`() {
        val legacyParameterTypes = arrayOf<Class<*>>(
            SessionStateStore::class.java,
            SessionMutexRegistry::class.java,
            IncarnationStateStore::class.java,
            IncarnationMutexRegistry::class.java,
            InferenceExecutor::class.java,
            VectorDeltaReducer::class.java,
            BackgroundDynamicsReducer::class.java,
        )
        val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val expectedParameterLists = listOf(
            legacyParameterTypes.toList(),
            legacyParameterTypes.toList() + Int::class.javaPrimitiveType!! + defaultConstructorMarker,
        )
        val actualParameterLists = VectorWriteService::class.java.declaredConstructors
            .map { constructor -> constructor.parameterTypes.toList() }
            .toSet()

        val missingParameterLists = expectedParameterLists.filterNot(actualParameterLists::contains)
        assertEquals(
            emptyList(),
            missingParameterLists,
            "Missing legacy VectorWriteService constructor parameter lists",
        )

        val direct = VectorWriteService::class.java.getConstructor(*legacyParameterTypes)
        assertFalse(direct.isSynthetic)
        val synthetic = VectorWriteService::class.java.getDeclaredConstructor(
            *legacyParameterTypes,
            Int::class.javaPrimitiveType!!,
            defaultConstructorMarker,
        )
        assertTrue(synthetic.isSynthetic)
        assertTrue(Modifier.isPublic(synthetic.modifiers))
    }

    @Test
    fun `keeps Kotlin defaults and no arg construction`() {
        assertIs<VectorWriteService>(
            VectorWriteService(
                inferenceExecutor = DirectInferenceExecutor,
                vectorDeltaReducer = VectorDeltaReducer(),
                backgroundDynamicsReducer = BackgroundDynamicsReducer.stationary(),
            ),
        )
        assertIs<VectorWriteService>(VectorWriteService::class.java.getConstructor().newInstance())
    }
}
