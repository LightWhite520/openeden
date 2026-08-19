package io.openeden.archive

interface DiaryArchiveReader {
    suspend fun page(incarnationId: String, limit: Int, before: String?): DiaryArchivePage
}
