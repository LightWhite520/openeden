package io.openeden.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DiaryArchiveContractTest {
    @Test
    fun `archive reader exposes immutable diary pages`() = runTest {
        val reader: DiaryArchiveReader = FakeDiaryArchiveReader(
            listOf(
                ArchivedDiaryEntry(
                    archiveEntryId = "archive-1",
                    incarnationId = "atri-1",
                    sourceDiaryId = "diary-1",
                    content = "diary text",
                    originalCreatedAtMs = 100L,
                    archivedAtMs = 200L,
                    archiveReason = "critical",
                    contentSha256 = "hash",
                ),
            ),
        )

        val page = reader.page("atri-1", 50, null)

        assertEquals(listOf("diary-1"), page.entries.map { it.sourceDiaryId })
        assertEquals(false, page.hasMore)
    }
}

private class FakeDiaryArchiveReader(
    private val entries: List<ArchivedDiaryEntry>,
) : DiaryArchiveReader {
    override suspend fun page(
        incarnationId: String,
        limit: Int,
        before: String?,
    ): DiaryArchivePage = DiaryArchivePage(
        entries = entries.filter { it.incarnationId == incarnationId }.take(limit),
        before = before,
        hasMore = false,
    )
}
