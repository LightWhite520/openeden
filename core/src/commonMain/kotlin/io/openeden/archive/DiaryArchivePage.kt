package io.openeden.archive

data class DiaryArchivePage(
    val entries: List<ArchivedDiaryEntry>,
    val before: String?,
    val hasMore: Boolean,
)
