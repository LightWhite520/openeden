package io.openeden.memory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object MemoryLineageSerializer : KSerializer<MemoryLineage> {
    @Serializable
    private data class Surrogate(
        val sourceTurnIds: List<String> = emptyList(),
        val sourceMemoryIds: List<String> = emptyList(),
        val lineageVersion: Int = MemoryLineage.CURRENT_VERSION,
    )

    private val surrogateSerializer = Surrogate.serializer()

    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: MemoryLineage) {
        encoder.encodeSerializableValue(
            surrogateSerializer,
            Surrogate(
                sourceTurnIds = value.sourceTurnIds,
                sourceMemoryIds = value.sourceMemoryIds,
                lineageVersion = value.lineageVersion,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): MemoryLineage {
        val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
        return try {
            MemoryLineage(
                sourceTurnIds = surrogate.sourceTurnIds,
                sourceMemoryIds = surrogate.sourceMemoryIds,
                lineageVersion = surrogate.lineageVersion,
            )
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Invalid memory lineage", error)
        }
    }
}
