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
