package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemoryLineageTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `lineage stores sorted distinct source identifiers`() {
        val lineage = MemoryLineage(
            sourceTurnIds = listOf("turn-2", "turn-1", "turn-2"),
            sourceMemoryIds = listOf("memory-b", "memory-a", "memory-b"),
        )

        assertEquals(listOf("turn-1", "turn-2"), lineage.sourceTurnIds)
        assertEquals(listOf("memory-a", "memory-b"), lineage.sourceMemoryIds)
        assertEquals(MemoryLineage.CURRENT_VERSION, lineage.lineageVersion)
        assertFalse(lineage.isEmpty)
    }

    @Test
    fun `empty lineage exposes the current version and empty state`() {
        assertEquals(MemoryLineage.CURRENT_VERSION, MemoryLineage.Empty.lineageVersion)
        assertTrue(MemoryLineage.Empty.isEmpty)
    }

    @Test
    fun `lineage decoding canonicalizes identifiers before exposing the value`() {
        val decoded = json.decodeFromJsonElement<MemoryLineage>(
            json.parseToJsonElement(
                """
                {
                    "sourceTurnIds": ["turn-2", "turn-1", "turn-2"],
                    "sourceMemoryIds": ["memory-b", "memory-a", "memory-b"],
                    "lineageVersion": 1
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("turn-1", "turn-2"), decoded.sourceTurnIds)
        assertEquals(listOf("memory-a", "memory-b"), decoded.sourceMemoryIds)
        assertEquals(
            json.parseToJsonElement(
                """
                {
                    "sourceTurnIds": ["turn-1", "turn-2"],
                    "sourceMemoryIds": ["memory-a", "memory-b"],
                    "lineageVersion": 1
                }
                """.trimIndent(),
            ),
            json.encodeToJsonElement(decoded),
        )
    }

    @Test
    fun `lineage decoding rejects invalid versions`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromJsonElement<MemoryLineage>(
                json.parseToJsonElement(
                    """
                    {
                        "sourceTurnIds": [],
                        "sourceMemoryIds": [],
                        "lineageVersion": 0
                    }
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `metadata defaults to empty lineage and no fingerprint`() {
        val metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.0f,
            deltaVec = VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "user",
        )

        assertEquals(MemoryLineage.Empty, metadata.lineage)
        assertEquals(null, metadata.contentFingerprint)
    }

    @Test
    fun `fingerprint normalizes CRLF and CR plus boundary whitespace`() = runTest {
        val canonical = "speaker: hello\nsecond line"

        assertEquals(
            MemoryContentFingerprint.of(canonical),
            MemoryContentFingerprint.of("  \r\nspeaker: hello\rsecond line\t "),
        )
    }

    @Test
    fun `fingerprint trims trailing whitespace from intermediate lines`() = runTest {
        val canonical = "first line\nsecond line\nthird line"

        assertEquals(
            MemoryContentFingerprint.of(canonical),
            MemoryContentFingerprint.of("first line  \nsecond line\t\nthird line"),
        )
    }

    @Test
    fun `fingerprint normalizes composed and decomposed Unicode`() = runTest {
        assertEquals(
            MemoryContentFingerprint.of("caf\u00e9"),
            MemoryContentFingerprint.of("cafe\u0301"),
        )
    }

    @Test
    fun `fingerprint keeps internal whitespace significant`() = runTest {
        assertFalse(
            MemoryContentFingerprint.of("speaker: hello\nsecond line") ==
                MemoryContentFingerprint.of("speaker:  hello\nsecond line"),
        )
    }

    @Test
    fun `fingerprint preserves embedded NUL characters`() = runTest {
        assertEquals("a\u0000b", MemoryContentFingerprint.normalize("a\u0000b"))
        assertNotEquals(
            MemoryContentFingerprint.of("a\u0000b"),
            MemoryContentFingerprint.of("ab"),
        )
    }

    @Test
    fun `fingerprint uses versioned SHA-256 of normalized visible content`() = runTest {
        assertEquals(
            "v1:sha-256:bd86b1fe64cc100eb0a68617731c6857f1309dd1c3d0d278c308d6fa5e835773",
            MemoryContentFingerprint.of("speaker: hello\nsecond line"),
        )
    }
}
