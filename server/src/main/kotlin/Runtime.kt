package io.openeden.server

import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.DevelopmentMessagePipeline
import io.openeden.runtime.HeartbeatScheduler
import io.openeden.runtime.LoggingHeartbeatDelivery
import io.openeden.runtime.HeartbeatOwner
import io.openeden.runtime.JvmInferenceExecutor
import io.openeden.runtime.OwnerHeartbeatRouteResolver
import io.openeden.runtime.RuntimeConfig
import io.openeden.runtime.RuntimeTickScheduler
import io.openeden.runtime.SecureRandomHeartbeatInterval
import io.openeden.runtime.SecureRandomSineWaveFluctuation
import io.openeden.runtime.SineWaveFluctuationEngine
import io.openeden.runtime.VectorWriteService
import io.openeden.server.db.SqlDelightSessionStateStore
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path

/** The shared, durable-backed pipeline, published for [configureRouting] to consume. */
val PipelineKey = AttributeKey<DevelopmentMessagePipeline>("openeden.pipeline")

/**
 * Boots the persona runtime: durable SQLite store → pipeline → heartbeat scheduler. Runs before
 * [configureRouting] (see application.yaml) so the route reads the shared pipeline from attributes.
 * The scheduler runs on its own dispatcher (§9.3.3) and is torn down on application stop.
 */
fun Application.configureRuntime() {
    val store = SqlDelightSessionStateStore.open(resolveRuntimeDbPath())
    // One VectorWriteService shared by the pipeline and the scheduler so all per-session writes
    // (user deltas + shock-heartbeat latch) serialize on the same Mutex registry (§14.2).
    val writer = VectorWriteService(store)
    val runtimeConfig = RuntimeConfig.Default.copy(owner = resolveHeartbeatOwner())
    val inferenceExecutor = JvmInferenceExecutor()
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = loadDefaultPersonaConfig(),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = inferenceExecutor,
    )
    attributes.put(PipelineKey, pipeline)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scheduler = HeartbeatScheduler(
        pipeline = pipeline,
        store = store,
        writer = writer,
        delivery = LoggingHeartbeatDelivery { log.info(it) },
        interval = SecureRandomHeartbeatInterval(),
        routeResolver = OwnerHeartbeatRouteResolver(runtimeConfig.owner),
    )
    val job = scheduler.start(scope)
    val tickJob = RuntimeTickScheduler(
        store = store,
        writer = writer,
        fluctuation = SineWaveFluctuationEngine(SecureRandomSineWaveFluctuation.profile()),
        inferenceExecutor = inferenceExecutor,
        config = runtimeConfig,
    ).start(scope)
    log.info("OpenEden heartbeat scheduler started")

    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        tickJob.cancel()
        scope.cancel()
        store.close()
        log.info("OpenEden runtime stopped")
    }
}

internal fun loadDefaultPersonaConfig(): PersonaConfig =
    PersonaFileLoader.load(resolveDefaultPersonaPath())

internal fun resolveDefaultPersonaPath(): Path = resolveFromRoot(Path.of("persona", "default.yaml"))

private fun resolveRuntimeDbPath(): Path = resolveFromRoot(Path.of("data", "runtime", "openeden.db"))

private fun resolveHeartbeatOwner(): HeartbeatOwner? {
    val platform = System.getenv("OPENEDEN_OWNER_PLATFORM")?.takeIf { it.isNotBlank() }
    val userId = System.getenv("OPENEDEN_OWNER_USER_ID")?.takeIf { it.isNotBlank() }
    return if (platform != null && userId != null) HeartbeatOwner(platform, userId) else null
}

/** Walk up from the working dir to find a project-relative path, falling back to the relative path. */
private fun resolveFromRoot(relative: Path): Path {
    var current: Path? = Path.of("").toAbsolutePath()
    repeat(6) {
        val dir = current ?: return relative
        val candidate = dir.resolve(relative)
        if (Files.exists(candidate) || Files.exists(dir.resolve("settings.gradle.kts"))) return candidate
        current = dir.parent
    }
    return relative
}
