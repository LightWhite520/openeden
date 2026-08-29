package io.openeden.server.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.incarnation.IncarnationMutexRegistry
import io.openeden.runtime.incarnation.IncarnationTurnGate
import io.openeden.runtime.incarnation.MutableIncarnationStateStore
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.state.VectorWriteService
import io.openeden.server.db.Database
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationMaintenanceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncarnationResetServiceTest {
    @Test
    fun `reset clears all incarnation layers and creates one fresh active incarnation`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()

            val result = fixture.reset(export)

            assertEquals("active-incarnation", result.previousIncarnationId)
            assertEquals("fresh-incarnation", result.activeIncarnationId)
            assertEquals(IncarnationLifecycle.ACTIVE, result.lifecycle)
            assertEquals(PersonaMode.GROWTH, result.personaMode)
            assertEquals(PersonaSubState.PRE_COMMAND, result.personaStartSubState)
            fixture.erasedTables.forEach { table ->
                assertEquals(0L, fixture.count(table), "Expected $table to be erased")
            }
            assertEquals(1L, fixture.count("incarnation_state"))
            assertEquals(1L, fixture.countWhere("incarnation_state", "lifecycle_status = 'ACTIVE'"))
            assertEquals("fresh-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
            assertEquals("growth", fixture.scalar("SELECT persona_mode FROM incarnation_state"))
            assertEquals("pre_command", fixture.scalar("SELECT persona_start_sub_state FROM incarnation_state"))
            assertEquals("0", fixture.scalar("SELECT evolution_index FROM incarnation_state"))
            assertEquals("0.0", fixture.scalar("SELECT omega FROM incarnation_state"))
            assertFalse(fixture.scalar("SELECT vector_json IS NULL FROM incarnation_state").toBoolean())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `repeating a successful request id returns the durable result without another incarnation`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val first = fixture.reset(export)

            val repeated = fixture.reset(export)

            assertEquals(first, repeated)
            assertEquals("fresh-incarnation", repeated.activeIncarnationId)
            assertEquals(1L, fixture.count("incarnation_reset_requests"))
            assertEquals(1L, fixture.count("incarnation_state"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed replay returns durable result after archive is removed`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val first = fixture.reset(export)
            export.directory.toFile().deleteRecursively()

            val replay = fixture.service.reset(fixture.request(export))

            assertEquals(first, replay)
            assertEquals(1L, fixture.count("incarnation_reset_requests"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `database admits at most one incomplete reset globally across previous incarnations`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            fixture.execute(
                """
                INSERT INTO incarnation_reset_requests(
                    request_id,previous_incarnation_id,fresh_incarnation_id,manifest_sha256,manifest_path,
                    payload_sha256,persona_mode,persona_start_sub_state,confirmed,phase,
                    projection_models_json,prepared_at_ms
                ) VALUES ('other-request','active-incarnation','fresh-2','hash','path','payload',
                    'growth','pre_command',1,'PREPARED','[]',2000)
                """.trimIndent(),
            )

            assertFailsWith<java.sql.SQLException> {
                fixture.execute(
                    """
                    INSERT INTO incarnation_reset_requests(
                        request_id,previous_incarnation_id,fresh_incarnation_id,manifest_sha256,manifest_path,
                        payload_sha256,persona_mode,persona_start_sub_state,confirmed,phase,
                        projection_models_json,prepared_at_ms
                    ) VALUES ('competing-request','different-previous-incarnation','fresh-3','hash-2','path-2','payload-2',
                        'growth','pre_command',1,'PREPARED','[]',2001)
                    """.trimIndent(),
                )
            }
            assertEquals(1L, fixture.countWhere("incarnation_reset_requests", "phase <> 'COMPLETED'"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `readiness reports zero active incarnations without active id lookup failure`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            fixture.execute("UPDATE incarnation_state SET lifecycle_status = 'TERMINATED'")

            val readiness = fixture.liveMaintenance().readiness()

            assertEquals(0L, readiness.activeIncarnationCount)
            assertEquals(null, readiness.activeIncarnationId)
            assertEquals("INVALID_ACTIVE_INCARNATION_COUNT", readiness.resetReadiness)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resume required rejects new maintenance but permits completed replay without archive`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val completed = fixture.reset(export)
            fixture.execute(
                """
                INSERT INTO incarnation_reset_requests(
                    request_id,previous_incarnation_id,fresh_incarnation_id,manifest_sha256,manifest_path,
                    payload_sha256,persona_mode,persona_start_sub_state,confirmed,phase,
                    projection_models_json,prepared_at_ms
                ) VALUES ('resume-required','fresh-incarnation','future','hash','missing','payload',
                    'growth','pre_command',1,'PREPARED','[]',4000)
                """.trimIndent(),
            )
            export.directory.toFile().deleteRecursively()
            val live = fixture.liveMaintenance()

            assertFailsWith<IllegalStateException> {
                live.export(IncarnationMaintenanceExportDto("fresh-incarnation", fixture.exportRoot.resolve("blocked").toString()))
            }
            assertFailsWith<IllegalStateException> {
                live.reset(
                    IncarnationMaintenanceResetDto(
                        "fresh-incarnation", "new-request", "missing.json", true, "growth", "pre_command",
                    ),
                )
            }
            val replay = live.reset(
                IncarnationMaintenanceResetDto(
                    "active-incarnation", "production-test-reset", export.manifestPath.toString(), true,
                    "growth", "pre_command",
                ),
            )
            assertEquals(completed.activeIncarnationId, replay.activeIncarnationId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stale incarnation id is rejected without mutation`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()

            val failure = fixture.rejection(
                fixture.request(export).copy(incarnationId = "stale-incarnation"),
            )

            assertEquals(IncarnationResetRejection.STALE_INCARNATION_ID, failure.reason)
            assertEquals("active-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
            assertEquals(1L, fixture.count("conversation_turns"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `blank request id and missing confirmation are rejected`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()

            assertEquals(
                IncarnationResetRejection.BLANK_REQUEST_ID,
                fixture.rejection(fixture.request(export).copy(requestId = "  ")).reason,
            )
            assertEquals(
                IncarnationResetRejection.CONFIRMATION_REQUIRED,
                fixture.rejection(fixture.request(export).copy(confirmed = false)).reason,
            )
            assertEquals("active-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `manifest for another incarnation is rejected`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val raw = export.manifestPath.readText()
            export.manifestPath.writeText(raw.replace("active-incarnation", "other-incarnation"))

            val failure = fixture.rejection(fixture.request(export))

            assertEquals(IncarnationResetRejection.EXPORT_INCARNATION_MISMATCH, failure.reason)
            assertEquals(1L, fixture.count("conversation_turns"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `incomplete and hash invalid manifests are rejected`() = runTest {
        val incompleteFixture = MaintenanceFixture()
        try {
            val export = incompleteFixture.export()
            export.manifestPath.writeText(
                export.manifestPath.readText().replace("COMPLETED", "IN_PROGRESS"),
            )
            assertEquals(
                IncarnationResetRejection.EXPORT_INCOMPLETE,
                incompleteFixture.rejection(incompleteFixture.request(export)).reason,
            )
        } finally {
            incompleteFixture.close()
        }

        val tamperedFixture = MaintenanceFixture()
        try {
            val export = tamperedFixture.export()
            Files.writeString(
                export.directory.resolve(export.manifest.files.first().name),
                "tampered",
                StandardOpenOption.APPEND,
            )
            assertEquals(
                IncarnationResetRejection.EXPORT_HASH_INVALID,
                tamperedFixture.rejection(tamperedFixture.request(export)).reason,
            )
            assertEquals(1L, tamperedFixture.count("memory_entries"))
        } finally {
            tamperedFixture.close()
        }
    }

    @Test
    fun `export is deterministic complete and durable before it is reported`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val first = fixture.export("export-a")
            val second = fixture.export("export-b")

            assertEquals(IncarnationExportStatus.COMPLETED, first.manifest.status)
            assertEquals(first.manifest.files, second.manifest.files)
            assertEquals(first.manifest.payloadSha256, second.manifest.payloadSha256)
            assertEquals(first.manifest.transcriptCount, 1L)
            assertEquals(first.manifest.memoryCount, 1L)
            assertEquals(first.manifest.relationshipEventCount, 1L)
            assertTrue(first.manifest.files.all { file -> Files.isRegularFile(first.directory.resolve(file.name)) })
            assertEquals(first.manifest, fixture.exporter.verify(first.manifestPath))
            assertFalse(Files.exists(first.directory.resolveSibling("${first.directory.fileName}.staging")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `export fsyncs staging and parent directories around atomic rename`() = runTest {
        val durability = RecordingExportDurability()
        val fixture = MaintenanceFixture(exportDurability = durability)
        try {
            fixture.export()

            val moveIndex = durability.events.indexOfFirst { it.startsWith("move:") }
            assertTrue(moveIndex > 1)
            assertTrue(durability.events.take(moveIndex).any { it.endsWith("export.staging") })
            assertTrue(durability.events.take(moveIndex).any { it.endsWith("exports") })
            assertTrue(durability.events.drop(moveIndex + 1).any { it.endsWith("exports") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `export revalidates staging and parent identity before first write`() = runTest {
        val events = mutableListOf<String>()
        val guard = RecordingExportPathGuard(events)
        val durability = RecordingExportDurability(events)
        val fixture = MaintenanceFixture(exportDurability = durability, exportPathGuardFactory = { guard })
        try {
            fixture.export()

            val firstWrite = events.indexOfFirst { it.startsWith("write:") }
            assertTrue(firstWrite > 0)
            assertTrue(events.take(firstWrite).contains("prepare"))
            assertTrue(events.take(firstWrite).contains("revalidate"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production export rejects staging replacement without writing outside configured root`() = runTest {
        val outside = Files.createTempDirectory("openeden-export-outside")
        var revalidations = 0
        val fixture = MaintenanceFixture(
            exportPathGuardFactory = { root ->
                val secure = SecureNioIncarnationExportPathGuard(root)
                object : IncarnationExportPathGuard by secure {
                    override fun revalidate(paths: PreparedIncarnationExportPaths) {
                        secure.revalidate(paths)
                        if (revalidations++ == 0) {
                            Files.move(paths.staging, paths.staging.resolveSibling("parked-staging"))
                            Files.createSymbolicLink(paths.staging, outside)
                        }
                    }
                }
            },
        )
        try {
            assertFailsWith<Exception> { fixture.export() }
            assertEquals(emptyList(), Files.list(outside).use { it.toList() })
        } finally {
            fixture.close()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `export rejects a staging leaf substituted at the final publication boundary`() = runTest {
        var substituted = false
        val fixture = MaintenanceFixture(
            exportPathGuardFactory = {
                object : IncarnationExportPathGuard {
                    override fun prepare(target: java.nio.file.Path): PreparedIncarnationExportPaths =
                        NioIncarnationExportPathGuard.prepare(target)

                    override fun revalidate(paths: PreparedIncarnationExportPaths) {
                        NioIncarnationExportPathGuard.revalidate(paths)
                        if (!substituted && Files.exists(paths.staging.resolve("manifest.json"))) {
                            Files.move(paths.staging, paths.staging.resolveSibling("parked-staging"))
                            Files.createDirectory(paths.staging)
                            substituted = true
                        }
                    }
                }
            },
        )
        try {
            assertFailsWith<IllegalStateException> { fixture.export() }
            assertTrue(substituted)
            assertFalse(Files.exists(fixture.exportRoot.resolve("export"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `export rejects target rebound after published handle verification`() = runTest {
        var substituted = false
        var secureIdentityAvailable = false
        val fixture = MaintenanceFixture(
            exportPathGuardFactory = { root ->
                Files.createDirectories(root)
                val delegate: IncarnationExportPathGuard = Files.newDirectoryStream(root).use { opened ->
                    if (opened is java.nio.file.SecureDirectoryStream<*>) {
                        secureIdentityAvailable = true
                        SecureNioIncarnationExportPathGuard(root)
                    } else {
                        NioIncarnationExportPathGuard
                    }
                }
                object : IncarnationExportPathGuard by delegate {
                    override fun publish(
                        paths: PreparedIncarnationExportPaths,
                        atomicMove: (java.nio.file.Path, java.nio.file.Path) -> Unit,
                    ) {
                        delegate.publish(paths, atomicMove)
                        Files.move(paths.target, paths.target.resolveSibling("parked-published-export"))
                        Files.createDirectory(paths.target)
                        if (secureIdentityAvailable) {
                            Files.writeString(paths.target.resolve(".openeden-export-identity"), paths.publicationToken)
                        }
                        Files.writeString(paths.target.resolve("substituted.txt"), "not the exported data")
                        substituted = true
                    }

                    override fun finalizePublication(paths: PreparedIncarnationExportPaths) {
                        delegate.finalizePublication(paths)
                    }
                }
            },
        )
        try {
            assertFailsWith<IllegalStateException> { fixture.export() }
            assertTrue(substituted)
            assertTrue(Files.exists(fixture.exportRoot.resolve("export/substituted.txt")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `live maintenance validates client paths and persona on its filesystem dispatcher`() = runTest {
        val dispatcher = RecordingCoroutineDispatcher()
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export("existing")
            val maintenance = fixture.liveMaintenance(dispatcher)

            assertFailsWith<IncarnationMaintenanceValidationException> {
                maintenance.export(IncarnationMaintenanceExportDto("", fixture.exportRoot.resolve("blank").toString()))
            }
            assertFailsWith<IncarnationMaintenanceValidationException> {
                maintenance.export(IncarnationMaintenanceExportDto("active-incarnation", export.directory.toString()))
            }
            assertFailsWith<IncarnationMaintenanceValidationException> {
                maintenance.reset(
                    IncarnationMaintenanceResetDto(
                        incarnationId = "active-incarnation",
                        requestId = "invalid-legacy",
                        manifestPath = export.manifestPath.toString(),
                        confirmed = true,
                        personaMode = "legacy",
                        personaStartSubState = "pre_command",
                    ),
                )
            }
            assertFailsWith<IncarnationMaintenanceValidationException> {
                maintenance.reset(
                    IncarnationMaintenanceResetDto(
                        incarnationId = "active-incarnation",
                        requestId = "missing-manifest",
                        manifestPath = fixture.exportRoot.resolve("missing/manifest.json").toString(),
                        confirmed = true,
                        personaMode = "growth",
                        personaStartSubState = "pre_command",
                    ),
                )
            }

            assertTrue(dispatcher.dispatchCount.get() >= 1)
        } finally {
            dispatcher.close()
            fixture.close()
        }
    }

    @Test
    fun `reset shares the incarnation mutation gate with runtime writes`() = runTest {
        val incarnationStore = MutableIncarnationStateStore()
        incarnationStore.readOrCreate("active-incarnation", PersonaMode.GROWTH, PersonaSubState.PRE_COMMAND)
        val writer = VectorWriteService(incarnationStore = incarnationStore)
        val resetEnteredProjection = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val fixture = MaintenanceFixture(
            gate = writer.incarnationMutationGate,
            projectionEraser = IncarnationProjectionEraser { _, _ ->
                resetEnteredProjection.complete(Unit)
                releaseReset.await()
            },
        )
        try {
            val export = fixture.export()
            val reset = async { fixture.reset(export) }
            resetEnteredProjection.await()
            val turnMutation = async {
                writer.updateIncarnation("active-incarnation") { state ->
                    state.copy(evolutionIndex = state.evolutionIndex + 1)
                }
            }
            testScheduler.runCurrent()
            assertFalse(turnMutation.isCompleted)

            releaseReset.complete(Unit)
            assertEquals("fresh-incarnation", reset.await().activeIncarnationId)
            assertEquals(1L, turnMutation.await().evolutionIndex)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reset erases the old incarnation vector projection before committing sqlite`() = runTest {
        val erased = mutableListOf<Pair<String, Set<String>>>()
        val fixture = MaintenanceFixture(
            projectionEraser = IncarnationProjectionEraser { incarnationId, modelIds ->
                erased += incarnationId to modelIds
            },
        )
        try {
            val export = fixture.export()

            fixture.reset(export)

            assertEquals(listOf("active-incarnation" to setOf("model")), erased)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `projection erasure failure leaves sqlite state untouched`() = runTest {
        val fixture = MaintenanceFixture(
            projectionEraser = IncarnationProjectionEraser { _, _ -> error("projection unavailable") },
        )
        try {
            val export = fixture.export()

            assertFailsWith<IllegalStateException> { fixture.reset(export) }

            assertEquals("active-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
            assertEquals(1L, fixture.count("memory_entries"))
            assertEquals("PREPARED", fixture.scalar("SELECT phase FROM incarnation_reset_requests"))
            assertEquals(
                "RESET_RECOVERABLE",
                fixture.scalar("SELECT status FROM memory_vector_sync WHERE memory_id = 'memory-1'"),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `post projection final sql failure remains recoverable and resumes without another erase`() = runTest {
        val erased = mutableListOf<Pair<String, Set<String>>>()
        val fixture = MaintenanceFixture(
            projectionEraser = IncarnationProjectionEraser { incarnationId, modelIds ->
                erased += incarnationId to modelIds
            },
        )
        try {
            val export = fixture.export()
            fixture.execute(
                """
                CREATE TRIGGER fail_fresh_incarnation
                BEFORE UPDATE ON incarnation_state
                BEGIN SELECT RAISE(FAIL, 'forced final sql failure'); END
                """.trimIndent(),
            )

            assertFailsWith<Exception> { fixture.reset(export) }

            assertEquals("PROJECTIONS_VERIFIED", fixture.scalar("SELECT phase FROM incarnation_reset_requests"))
            assertEquals("active-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
            assertEquals(
                "RESET_RECOVERABLE",
                fixture.scalar("SELECT status FROM memory_vector_sync WHERE memory_id = 'memory-1'"),
            )
            fixture.execute("DROP TRIGGER fail_fresh_incarnation")

            assertEquals("fresh-incarnation", fixture.reset(export).activeIncarnationId)
            assertEquals(1, erased.size, "verified projection erasure must not repeat after final-SQL failure")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `prepared crash resumes projection erase and completes with the same fresh id`() = runTest {
        var attempts = 0
        val fixture = MaintenanceFixture(
            projectionEraser = IncarnationProjectionEraser { _, _ ->
                attempts += 1
                if (attempts == 1) error("simulated crash after prepare")
            },
        )
        try {
            val export = fixture.export()

            assertFailsWith<IllegalStateException> { fixture.reset(export) }
            assertEquals("PREPARED", fixture.scalar("SELECT phase FROM incarnation_reset_requests"))
            assertEquals("fresh-incarnation", fixture.scalar("SELECT fresh_incarnation_id FROM incarnation_reset_requests"))

            fixture.service.resumeIncomplete()
            val resumed = checkNotNull(fixture.repository.completedReset("production-test-reset"))

            assertEquals("fresh-incarnation", resumed.activeIncarnationId)
            assertEquals(2, attempts)
            assertEquals("COMPLETED", fixture.scalar("SELECT phase FROM incarnation_reset_requests"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `export and reset retain every row owned by another incarnation`() = runTest {
        val fixture = MaintenanceFixture(seedOtherIncarnation = true)
        try {
            val export = fixture.export()

            assertEquals(1L, export.manifest.transcriptCount)
            assertEquals(1L, export.manifest.memoryCount)
            assertEquals(1L, export.manifest.relationshipEventCount)
            fixture.reset(export)

            fixture.retainedTableCounts.forEach { (table, expected) ->
                assertEquals(expected, fixture.count(table), "Unexpected retained count for $table")
            }
            assertEquals(1L, fixture.countWhere("conversation_turns", "incarnation_id = 'other-incarnation'"))
            assertEquals(1L, fixture.countWhere("memory_entries", "incarnation_id = 'other-incarnation'"))
            assertEquals(1L, fixture.countWhere("diary_archive", "incarnation_id = 'other-incarnation'"))
            assertEquals(1L, fixture.countWhere("relationship_events", "incarnation_id = 'other-incarnation'"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reset retains same session id rows owned by another incarnation`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            fixture.execute(
                """
                INSERT INTO session_state(
                    incarnation_id,session_id,vector_json,origin_json,omega,evolution_index,
                    persona_mode,persona_start_sub_state
                ) VALUES (
                    'other-incarnation','QQ:scope','[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',0.1,4,'growth','pre_command'
                )
                """.trimIndent(),
            )
            fixture.execute(
                "INSERT INTO prompt_history_state(incarnation_id,session_id,cache_epoch,serializer_version,updated_at_ms) " +
                    "VALUES ('other-incarnation','QQ:scope',7,1,500)",
            )
            fixture.execute(
                "INSERT INTO diary_checkpoints(incarnation_id,session_id,last_covered_raw_memory_id) " +
                    "VALUES ('other-incarnation','QQ:scope','other-memory')",
            )
            val export = fixture.export()

            fixture.reset(export)

            assertEquals(1L, fixture.countWhere("session_state", "incarnation_id = 'other-incarnation'"))
            assertEquals(1L, fixture.countWhere("prompt_history_state", "incarnation_id = 'other-incarnation'"))
            assertEquals(1L, fixture.countWhere("diary_checkpoints", "incarnation_id = 'other-incarnation'"))
            assertEquals(0L, fixture.countWhere("session_state", "incarnation_id = 'active-incarnation'"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reset exports and erases transcript free session state by direct ownership`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            fixture.execute(
                """
                INSERT INTO session_state(
                    incarnation_id,session_id,vector_json,origin_json,omega,evolution_index,
                    persona_mode,persona_start_sub_state
                ) VALUES (
                    'active-incarnation','QQ:no-transcript','[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',0.2,2,'growth','pre_command'
                )
                """.trimIndent(),
            )

            val export = fixture.export()
            val sessionFile = export.directory.resolve("legacy-session-state.jsonl").readText()
            assertTrue(sessionFile.contains("QQ:no-transcript"))

            fixture.reset(export)

            assertEquals(0L, fixture.countWhere("session_state", "incarnation_id = 'active-incarnation'"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `projection configuration fails closed when persisted models cannot be enumerated`() = runTest {
        val fixture = MaintenanceFixture(projectionEraser = IncarnationProjectionEraser.Disabled)
        try {
            val export = fixture.export()

            val failure = assertFailsWith<IncarnationProjectionConfigurationException> {
                fixture.reset(export)
            }

            assertTrue(failure.message.orEmpty().contains("model"))
            assertEquals("PREPARED", fixture.scalar("SELECT phase FROM incarnation_reset_requests"))
            assertEquals("active-incarnation", fixture.scalar("SELECT active_incarnation_id FROM incarnation_state"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `request id normalization replays only an identical request fingerprint`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val padded = fixture.request(export).copy(requestId = "  production-test-reset  ")

            val first = fixture.service.reset(padded)
            val replay = fixture.service.reset(fixture.request(export))

            assertEquals(first, replay)
            assertEquals("production-test-reset", replay.requestId)
            assertEquals(1L, fixture.count("incarnation_reset_requests"))

            val conflict = fixture.rejection(
                fixture.request(export).copy(personaStartSubState = PersonaSubState.TRUE_SELF),
            )
            assertEquals(IncarnationResetRejection.REQUEST_ID_CONFLICT, conflict.reason)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `request id reuse rejects previous incarnation and manifest hash conflicts`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val firstExport = fixture.export("export-first")
            fixture.advanceExportClock()
            val secondExport = fixture.export("export-second")
            fixture.reset(firstExport)

            assertEquals(
                IncarnationResetRejection.REQUEST_ID_CONFLICT,
                fixture.rejection(
                    fixture.request(firstExport).copy(incarnationId = "different-incarnation"),
                ).reason,
            )
            assertEquals(
                IncarnationResetRejection.REQUEST_ID_CONFLICT,
                fixture.rejection(fixture.request(secondExport)).reason,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `request id reuse rejects a different manifest path with identical content`() = runTest {
        val fixture = MaintenanceFixture()
        try {
            val export = fixture.export()
            val copiedDirectory = export.directory.resolveSibling("export-copy")
            check(export.directory.toFile().copyRecursively(copiedDirectory.toFile()))
            fixture.reset(export)

            val conflict = fixture.rejection(
                fixture.request(export).copy(manifestPath = copiedDirectory.resolve("manifest.json")),
            )

            assertEquals(IncarnationResetRejection.REQUEST_ID_CONFLICT, conflict.reason)
        } finally {
            fixture.close()
        }
    }
}

private class MaintenanceFixture(
    projectionEraser: IncarnationProjectionEraser = IncarnationProjectionEraser { _, _ -> },
    val gate: IncarnationTurnGate = IncarnationTurnGate(IncarnationMutexRegistry()),
    private val seedOtherIncarnation: Boolean = false,
    private val exportDurability: IncarnationExportDurability = NioIncarnationExportDurability,
    private val exportPathGuardFactory: (java.nio.file.Path) -> IncarnationExportPathGuard = {
        NioIncarnationExportPathGuard
    },
) {
    private val tempDir = Files.createTempDirectory("openeden-maintenance-test")
    private val dbPath = tempDir.resolve("openeden.db")
    val exportRoot = tempDir.resolve("exports")
    private var exportTimestamp = 2_000L
    val repository: SqlDelightIncarnationMaintenanceRepository
    val exporter: IncarnationDataExporter
    val service: IncarnationResetService

    val erasedTables = listOf(
        "conversation_turns",
        "turn_post_commit",
        "prompt_history_state",
        "prompt_history_chunks",
        "prompt_history_compactions",
        "session_state",
        "memory_entries",
        "memory_embeddings",
        "memory_vector_sync",
        "diary_tasks",
        "diary_checkpoints",
        "diary_archive",
        "relationship_state",
        "relationship_events",
        "trace_spans",
    )

    val retainedTableCounts = erasedTables.associateWith { 1L }

    init {
        createSchema()
        seedAllLayers()
        repository = SqlDelightIncarnationMaintenanceRepository.open(dbPath)
        exporter = IncarnationDataExporter(
            repository = repository,
            mutationGate = gate,
            nowMs = { exportTimestamp },
            durability = exportDurability,
            pathGuard = exportPathGuardFactory(exportRoot),
        )
        service = IncarnationResetService(
            repository = repository,
            exporter = exporter,
            mutationGate = gate,
            projectionEraser = projectionEraser,
            nowMs = { 3_000L },
            freshIncarnationId = { "fresh-incarnation" },
        )
    }

    suspend fun export(name: String = "export"): IncarnationExportResult = exporter.export(
        IncarnationExportRequest(
            incarnationId = "active-incarnation",
            targetDirectory = exportRoot.resolve(name),
        ),
    )

    fun advanceExportClock() {
        exportTimestamp += 1L
    }

    fun request(export: IncarnationExportResult) = IncarnationResetRequest(
        incarnationId = "active-incarnation",
        requestId = "production-test-reset",
        manifestPath = export.manifestPath,
        confirmed = true,
        personaMode = PersonaMode.GROWTH,
        personaStartSubState = PersonaSubState.PRE_COMMAND,
    )

    suspend fun reset(export: IncarnationExportResult): IncarnationResetResult = service.reset(request(export))

    fun liveMaintenance(
        fileIoDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    ) = LiveServerIncarnationMaintenance(repository, exporter, service, exportRoot, fileIoDispatcher)

    suspend fun rejection(request: IncarnationResetRequest): IncarnationResetRejectedException = try {
        service.reset(request)
        error("Expected reset rejection")
    } catch (failure: IncarnationResetRejectedException) {
        failure
    }

    fun count(table: String): Long = scalar("SELECT COUNT(*) FROM $table").toLong()

    fun countWhere(table: String, predicate: String): Long =
        scalar("SELECT COUNT(*) FROM $table WHERE $predicate").toLong()

    fun scalar(sql: String): String = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    fun execute(sql: String) = connection().use { connection ->
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    suspend fun close() {
        repository.close()
        tempDir.toFile().deleteRecursively()
    }

    private fun createSchema() {
        JdbcSqliteDriver(
            "jdbc:sqlite:${dbPath.toAbsolutePath()}",
            Properties(),
            Database.Schema,
        ).close()
    }

    private fun seedAllLayers() = connection().use { connection ->
        connection.autoCommit = false
        try {
            seed(connection)
            if (seedOtherIncarnation) seedOtherIncarnation(connection)
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO incarnation_state(
                    singleton_id, active_incarnation_id, created_at_ms, lifecycle_status,
                    lifecycle_changed_at_ms, vector_json, origin_json, omega, evolution_index,
                    persona_mode, persona_start_sub_state, last_user_activity_ms, last_runtime_tick_at_ms,
                    shock_active, shock_intensity, shock_description, shock_triggered_at_ms,
                    shock_decay_lambda, shock_heartbeat_fired, last_vector_dynamics_at_ms,
                    centroid_revision, origin_revision
                ) VALUES (
                    1, 'active-incarnation', 1000, 'ACTIVE', 1000,
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    0.4, 17, 'growth', 'true_self', 900, 950,
                    1, 0.8, 'shock', 800, 0.01, 1, 975, 3, 2
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                "INSERT INTO conversation_turns VALUES ('turn-1','active-incarnation','QQ:scope','QQ','scope','user','hello','hi',1000)",
            )
            statement.executeUpdate(
                "INSERT INTO turn_post_commit VALUES ('turn-1','{}','[]')",
            )
            statement.executeUpdate(
                "INSERT INTO prompt_history_state(incarnation_id,session_id,cache_epoch,serializer_version,updated_at_ms) VALUES ('active-incarnation','QQ:scope',1,1,1000)",
            )
            statement.executeUpdate(
                """
                INSERT INTO prompt_history_chunks(
                    incarnation_id,chunk_id,session_id,cache_epoch,first_turn_id,last_turn_id,turn_ids_json,
                    items_json,serialized_text,token_count,fingerprint,serializer_version
                ) VALUES ('active-incarnation','chunk-1','QQ:scope',1,'turn-1','turn-1','["turn-1"]','[]',NULL,1,'chunk-fp',1)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO prompt_history_compactions(
                    incarnation_id,request_id,session_id,source_fingerprint,status,created_at_ms
                ) VALUES ('active-incarnation','compact-1','QQ:scope','source-fp','PENDING',1000)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO session_state(
                    incarnation_id,session_id,vector_json,origin_json,omega,evolution_index,persona_mode,
                    persona_start_sub_state,last_user_activity_ms,last_runtime_tick_at_ms
                ) VALUES (
                    'active-incarnation','QQ:scope','[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',0.4,17,'growth','true_self',900,950
                )
                """.trimIndent(),
            )
            statement.executeUpdate(memoryInsert)
            statement.executeUpdate(
                "INSERT INTO memory_embeddings VALUES ('memory-1','model','[0.1]','[0.2]','READY')",
            )
            statement.executeUpdate(
                "INSERT INTO memory_vector_sync VALUES ('memory-1','model','READY',0,1000,NULL,1000)",
            )
            statement.executeUpdate(diaryTaskInsert)
            statement.executeUpdate(
                "INSERT INTO diary_checkpoints VALUES ('active-incarnation','QQ:scope','memory-1',1000,'memory-1')",
            )
            statement.executeUpdate(
                "INSERT INTO diary_archive VALUES ('archive-1','active-incarnation','memory-1','diary',1000,1100,'test','hash')",
            )
            statement.executeUpdate(relationshipStateInsert)
            statement.executeUpdate(
                """
                INSERT INTO relationship_events(
                    event_id,incarnation_id,canonical_subject_id,source_turn_id,event_type,
                    confidence,evidence_digest,created_at_ms
                ) VALUES ('event-1','active-incarnation','subject','turn-1','POSITIVE',0.9,'evidence',1000)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO trace_spans(
                    span_id,trace_id,turn_id,session_id,stage,status,started_at_ms,tags_json,attributes_json
                ) VALUES ('span-1','trace-1','turn-1','QQ:scope','pipeline','OK',1000,'[]','{}')
                """.trimIndent(),
            )
        }
    }

    private fun seedOtherIncarnation(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO conversation_turns VALUES ('turn-2','other-incarnation','QQ:other','QQ','other','user-2','old','reply',500)",
            )
            statement.executeUpdate("INSERT INTO turn_post_commit VALUES ('turn-2','{}','[]')")
            statement.executeUpdate(
                "INSERT INTO prompt_history_state(incarnation_id,session_id,cache_epoch,serializer_version,updated_at_ms) VALUES ('other-incarnation','QQ:other',1,1,500)",
            )
            statement.executeUpdate(
                """
                INSERT INTO prompt_history_chunks(
                    incarnation_id,chunk_id,session_id,cache_epoch,first_turn_id,last_turn_id,turn_ids_json,
                    items_json,serialized_text,token_count,fingerprint,serializer_version
                ) VALUES ('other-incarnation','chunk-2','QQ:other',1,'turn-2','turn-2','["turn-2"]','[]',NULL,1,'chunk-fp-2',1)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO prompt_history_compactions(incarnation_id,request_id,session_id,source_fingerprint,status,created_at_ms)
                VALUES ('other-incarnation','compact-2','QQ:other','source-fp-2','PENDING',500)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO session_state(
                    incarnation_id,session_id,vector_json,origin_json,omega,evolution_index,persona_mode,
                    persona_start_sub_state,last_user_activity_ms,last_runtime_tick_at_ms
                ) VALUES ('other-incarnation','QQ:other','[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',
                    '[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]',0.1,4,'growth','pre_command',400,450)
                """.trimIndent(),
            )
            statement.executeUpdate(otherMemoryInsert)
            statement.executeUpdate("INSERT INTO memory_embeddings VALUES ('memory-2','other-model','[0.3]','[0.4]','READY')")
            statement.executeUpdate("INSERT INTO memory_vector_sync VALUES ('memory-2','other-model','READY',0,500,NULL,500)")
            statement.executeUpdate(otherDiaryTaskInsert)
            statement.executeUpdate("INSERT INTO diary_checkpoints VALUES ('other-incarnation','QQ:other','memory-2',500,'memory-2')")
            statement.executeUpdate(
                "INSERT INTO diary_archive VALUES ('archive-2','other-incarnation','memory-2','other diary',500,600,'test','hash-2')",
            )
            statement.executeUpdate(otherRelationshipStateInsert)
            statement.executeUpdate(
                """
                INSERT INTO relationship_events(
                    event_id,incarnation_id,canonical_subject_id,source_turn_id,event_type,
                    confidence,evidence_digest,created_at_ms
                ) VALUES ('event-2','other-incarnation','subject-2','turn-2','POSITIVE',0.7,'evidence-2',500)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO trace_spans(
                    span_id,trace_id,turn_id,session_id,stage,status,started_at_ms,tags_json,attributes_json
                ) VALUES ('span-2','trace-2','turn-2','QQ:other','pipeline','OK',500,'[]','{}')
                """.trimIndent(),
            )
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").also {
        it.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys = ON") }
    }

    private companion object {
        val memoryInsert = """
            INSERT INTO memory_entries(
                id,session_id,user_id,platform,room,kind,content,tags_json,created_at_ms,
                snapshot_l,snapshot_p,snapshot_e,snapshot_s,snapshot_tau,snapshot_v,snapshot_m,snapshot_f,
                omega_state,delta_l,delta_p,delta_e,delta_s,delta_tau,delta_v,delta_m,delta_f,
                origin_l,origin_p,origin_e,origin_s,origin_tau,origin_v,origin_m,origin_f,
                source_turn_ids_json,source_memory_ids_json,content_fingerprint,lineage_version,
                incarnation_id,source_session_id,canonical_subject_id,visibility_kind
            ) VALUES (
                'memory-1','QQ:scope','user','QQ','event_room','RAW','memory','[]',1000,
                0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,
                0.4,0.0,0.1,0.0,0.0,0.0,0.0,0.0,0.0,
                0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,
                '["turn-1"]','[]','memory-fp',1,
                'active-incarnation','QQ:scope','subject','PUBLIC'
            )
        """.trimIndent()

        val diaryTaskInsert = """
            INSERT INTO diary_tasks(
                id,session_id,source_memory_id,reason,status,attempts,created_at_ms,available_at_ms,
                incarnation_id,source_session_id,platform,diary_user_id,canonical_subject_id,visibility_kind
            ) VALUES (
                'diary-1','QQ:scope','memory-1','test','DONE',0,1000,1000,
                'active-incarnation','QQ:scope','QQ','user','subject','PUBLIC'
            )
        """.trimIndent()

        val relationshipStateInsert = """
            INSERT INTO relationship_state(
                incarnation_id,canonical_subject_id,trust,familiarity,safety,boundary_sensitivity,
                unresolved_tension,reciprocal_interest,evidence_count,updated_at_ms,phase,
                preferred_addresses_json,continuous_accumulator_version,accumulator_trust,
                accumulator_familiarity,accumulator_safety,accumulator_boundary_sensitivity,
                accumulator_unresolved_tension,accumulator_reciprocal_interest,
                continuous_baseline_event_ids_json
            ) VALUES (
                'active-incarnation','subject',0.7,0.6,0.8,0.1,0.2,0.5,1,1000,'FAMILIAR',
                '[]',1,0.7,0.6,0.8,0.1,0.2,0.5,'[]'
            )
        """.trimIndent()

        val otherMemoryInsert = memoryInsert
            .replace("memory-1", "memory-2")
            .replace("QQ:scope", "QQ:other")
            .replace("'user'", "'user-2'")
            .replace("'turn-1'", "'turn-2'")
            .replace("memory-fp", "memory-fp-2")
            .replace("active-incarnation", "other-incarnation")
            .replace("'subject'", "'subject-2'")

        val otherDiaryTaskInsert = diaryTaskInsert
            .replace("diary-1", "diary-2")
            .replace("memory-1", "memory-2")
            .replace("QQ:scope", "QQ:other")
            .replace("active-incarnation", "other-incarnation")
            .replace("'user'", "'user-2'")
            .replace("'subject'", "'subject-2'")

        val otherRelationshipStateInsert = relationshipStateInsert
            .replace("active-incarnation", "other-incarnation")
            .replace("'subject'", "'subject-2'")
    }
}

private class RecordingCoroutineDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val delegate: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val dispatchCount = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount.incrementAndGet()
        delegate.dispatch(context, block)
    }

    override fun close() = delegate.close()
}

private class RecordingExportDurability(
    val events: MutableList<String> = mutableListOf(),
) : IncarnationExportDurability {

    override fun forceWrite(path: java.nio.file.Path, bytes: ByteArray) {
        events += "write:${path.fileName}"
        NioIncarnationExportDurability.forceWrite(path, bytes)
    }

    override fun forceDirectory(path: java.nio.file.Path) {
        events += "directory:${path.fileName}"
    }

    override fun atomicMove(source: java.nio.file.Path, target: java.nio.file.Path) {
        events += "move:${source.fileName}->${target.fileName}"
        NioIncarnationExportDurability.atomicMove(source, target)
    }
}

private class RecordingExportPathGuard(
    private val events: MutableList<String>,
) : IncarnationExportPathGuard {
    override fun prepare(target: java.nio.file.Path): PreparedIncarnationExportPaths {
        events += "prepare"
        return NioIncarnationExportPathGuard.prepare(target)
    }

    override fun revalidate(paths: PreparedIncarnationExportPaths) {
        events += "revalidate"
        NioIncarnationExportPathGuard.revalidate(paths)
    }
}
