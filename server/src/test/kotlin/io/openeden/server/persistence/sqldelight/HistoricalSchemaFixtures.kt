package io.openeden.server.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

internal fun createPreV13DiaryTasks(driver: JdbcSqliteDriver) {
    driver.execute(
        null,
        """
        CREATE TABLE diary_tasks (
            id TEXT NOT NULL PRIMARY KEY,
            session_id TEXT NOT NULL,
            source_memory_id TEXT,
            reason TEXT NOT NULL,
            status TEXT NOT NULL,
            attempts INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,
            available_at_ms INTEGER NOT NULL,
            lease_expires_at_ms INTEGER,
            lease_token TEXT,
            last_error TEXT
        )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
        CREATE TABLE diary_checkpoints (
            session_id TEXT NOT NULL PRIMARY KEY,
            last_covered_raw_memory_id TEXT,
            last_successful_diary_at_ms INTEGER,
            last_narrative_memory_id TEXT
        )
        """.trimIndent(),
        0,
    )
}

internal fun createLegacyRelationshipState(driver: JdbcSqliteDriver) {
    driver.execute(
        null,
        """
        CREATE TABLE relationship_state (
            session_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            trust REAL NOT NULL,
            familiarity REAL NOT NULL,
            safety REAL NOT NULL,
            boundary_sensitivity REAL NOT NULL,
            unresolved_tension REAL NOT NULL,
            evidence_count INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL,
            PRIMARY KEY(session_id, user_id)
        )
        """.trimIndent(),
        0,
    )
}

internal fun createPreV21OwnedSessionTables(driver: JdbcSqliteDriver) {
    driver.execute(null, PRE_V21_SESSION_STATE_SQL, 0)
    driver.execute(null, PRE_V21_DIARY_CHECKPOINTS_SQL, 0)
}

internal fun createPreV21PromptHistoryTables(driver: JdbcSqliteDriver) {
    driver.execute(null, PRE_V21_PROMPT_STATE_SQL, 0)
    driver.execute(null, PRE_V21_PROMPT_CHUNKS_SQL, 0)
    driver.execute(null, PRE_V21_PROMPT_CHUNKS_INDEX_SQL, 0)
    driver.execute(null, PRE_V21_PROMPT_COMPACTIONS_SQL, 0)
    driver.execute(null, PRE_V21_PROMPT_COMPACTIONS_INDEX_SQL, 0)
}

internal val PRE_V21_SESSION_STATE_SQL = """
    CREATE TABLE session_state (
        session_id TEXT NOT NULL PRIMARY KEY, vector_json TEXT NOT NULL, origin_json TEXT NOT NULL,
        omega REAL NOT NULL, evolution_index INTEGER NOT NULL, persona_mode TEXT,
        persona_start_sub_state TEXT, last_user_activity_ms INTEGER, last_runtime_tick_at_ms INTEGER,
        shock_active INTEGER, shock_intensity REAL, shock_description TEXT,
        shock_triggered_at_ms INTEGER, shock_decay_lambda REAL, shock_heartbeat_fired INTEGER
    )
""".trimIndent()

internal val PRE_V21_DIARY_CHECKPOINTS_SQL = """
    CREATE TABLE diary_checkpoints (
        session_id TEXT NOT NULL PRIMARY KEY, last_covered_raw_memory_id TEXT,
        last_successful_diary_at_ms INTEGER, last_narrative_memory_id TEXT
    )
""".trimIndent()

private val PRE_V21_PROMPT_STATE_SQL = """
    CREATE TABLE prompt_history_state (
        session_id TEXT NOT NULL PRIMARY KEY, cache_epoch INTEGER NOT NULL,
        serializer_version INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, summary_text TEXT,
        summary_source_turn_ids_json TEXT, summary_fingerprint TEXT, summary_serializer_version INTEGER
    )
""".trimIndent()

private val PRE_V21_PROMPT_CHUNKS_SQL = """
    CREATE TABLE prompt_history_chunks (
        chunk_id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, cache_epoch INTEGER NOT NULL,
        first_turn_id TEXT NOT NULL, last_turn_id TEXT NOT NULL, turn_ids_json TEXT NOT NULL,
        items_json TEXT, serialized_text TEXT, token_count INTEGER NOT NULL,
        fingerprint TEXT NOT NULL, serializer_version INTEGER NOT NULL,
        CHECK (items_json IS NOT NULL OR serialized_text IS NOT NULL)
    )
""".trimIndent()

private const val PRE_V21_PROMPT_CHUNKS_INDEX_SQL =
    "CREATE INDEX prompt_history_chunks_session_epoch ON prompt_history_chunks(session_id, cache_epoch, first_turn_id, last_turn_id, chunk_id)"

private val PRE_V21_PROMPT_COMPACTIONS_SQL = """
    CREATE TABLE prompt_history_compactions (
        request_id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, source_fingerprint TEXT NOT NULL,
        status TEXT NOT NULL, result_fingerprint TEXT, result_snapshot_json TEXT,
        result_cache_epoch INTEGER, created_at_ms INTEGER NOT NULL, completed_at_ms INTEGER
    )
""".trimIndent()

private const val PRE_V21_PROMPT_COMPACTIONS_INDEX_SQL =
    "CREATE INDEX prompt_history_compactions_session_created ON prompt_history_compactions(session_id, created_at_ms, request_id)"
