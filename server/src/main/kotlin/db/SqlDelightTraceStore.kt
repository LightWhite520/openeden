package io.openeden.server.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.trace.TraceContext
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStatus
import io.openeden.trace.TraceStore
import io.openeden.trace.TraceSanitizer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class SqlDelightTraceStore(
    private val database: Database,
    private val driver: SqlDriver? = null,
    private val json: Json = Json,
) : TraceStore {
    private val queries get() = database.memoryQueries

    override suspend fun append(span: TraceSpan) {
        val safe = TraceSanitizer.sanitize(span)
        queries.insertTraceSpan(
            span_id = safe.spanId,
            trace_id = safe.context.traceId,
            turn_id = safe.context.turnId,
            session_id = safe.context.sessionId,
            parent_span_id = safe.parentSpanId,
            stage = safe.stage,
            status = safe.status.name,
            started_at_ms = safe.startedAtMs,
            finished_at_ms = safe.finishedAtMs,
            tags_json = json.encodeToString(safe.tags.toList()),
            attributes_json = json.encodeToString(safe.attributes),
            error_code = safe.errorCode,
            error_summary = safe.errorSummary,
        )
    }

    fun readAll(): List<TraceSpan> = queries.selectTraceSpans(::map).executeAsList()
    fun close() = driver?.close()

    private fun map(
        spanId: String, traceId: String, turnId: String, sessionId: String, parentSpanId: String?,
        stage: String, status: String, startedAtMs: Long, finishedAtMs: Long?, tagsJson: String,
        attributesJson: String, errorCode: String?, errorSummary: String?,
    ): TraceSpan = TraceSpan(
        context = TraceContext(traceId, turnId, sessionId),
        parentSpanId = parentSpanId,
        spanId = spanId,
        stage = stage,
        status = TraceStatus.valueOf(status),
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
        tags = json.decodeFromString(tagsJson),
        attributes = json.decodeFromString(attributesJson),
        errorCode = errorCode,
        errorSummary = errorSummary,
    )

    companion object {
        fun open(dbPath: Path): SqlDelightTraceStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", Properties(), Database.Schema)
            return SqlDelightTraceStore(Database(driver), driver)
        }
    }
}
