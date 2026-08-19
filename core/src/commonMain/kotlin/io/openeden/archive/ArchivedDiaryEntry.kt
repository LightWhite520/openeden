package io.openeden.archive

data class ArchivedDiaryEntry(
    val archiveEntryId: String,
    val incarnationId: String,
    val sourceDiaryId: String,
    val content: String,
    val originalCreatedAtMs: Long,
    val archivedAtMs: Long,
    val archiveReason: String,
    val contentSha256: String,
)
