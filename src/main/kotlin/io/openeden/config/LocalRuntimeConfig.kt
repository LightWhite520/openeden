package io.openeden.config

import java.nio.file.Path

data class LocalRuntimeConfig(
    val localUserId: String,
    val personaPath: Path,
    val runtimeDbPath: Path,
    val localModelArtifactPath: Path?,
    val localModelArtifactUrl: String,
    val llm: LlmProviderConfig,
    val modelBackend: String = "artifact",
    val djlVqVaeModelPath: Path? = null,
    val djlTextModelPath: Path? = null,
    val djlEmotionalModelPath: Path? = null,
    val djlEngineName: String = "PyTorch",
    val djlModelName: String = "model",
) {
    fun requireProviderCredentials(): LocalRuntimeConfig {
        if (llm.provider == "openai" && llm.openAiApiKey.isNullOrBlank()) {
            throw IllegalArgumentException("OPENEDEN_OPENAI_API_KEY is required when OPENEDEN_LLM_PROVIDER=openai")
        }
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): LocalRuntimeConfig {
            val provider = env.valueOrDefault("OPENEDEN_LLM_PROVIDER", "openai").lowercase()
            require(provider == "openai") { "Unsupported OPENEDEN_LLM_PROVIDER: $provider" }
            return LocalRuntimeConfig(
                localUserId = env.valueOrDefault("OPENEDEN_LOCAL_USER_ID", "local"),
                personaPath = Path.of(env.valueOrDefault("OPENEDEN_PERSONA_PATH", "persona/default.yaml")),
                runtimeDbPath = Path.of(env.valueOrDefault("OPENEDEN_RUNTIME_DB_PATH", "data/runtime/openeden.db")),
                localModelArtifactPath = env["OPENEDEN_LOCAL_MODEL_ARTIFACT"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
                    ?: Path.of("data/models/local-model-artifact.json"),
                localModelArtifactUrl = env.valueOrDefault(
                    "OPENEDEN_LOCAL_MODEL_ARTIFACT_URL",
                    "https://huggingface.co/0x4C57/openeden-codebook-base-model/resolve/main/local-model-artifact.json",
                ),
                llm = LlmProviderConfig(
                    provider = provider,
                    model = env.valueOrDefault("OPENEDEN_OPENAI_MODEL", "gpt-5-mini"),
                    openAiBaseUrl = env.valueOrDefault("OPENEDEN_OPENAI_BASE_URL", "https://api.openai.com/v1"),
                    openAiApiKey = env["OPENEDEN_OPENAI_API_KEY"]?.takeIf { it.isNotBlank() },
                ),
                modelBackend = env.valueOrDefault("OPENEDEN_MODEL_BACKEND", "djl").lowercase(),
                djlVqVaeModelPath = env["OPENEDEN_DJL_VQVAE_MODEL_PATH"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: Path.of("data/models/djl/vqvae"),
                djlTextModelPath = env["OPENEDEN_DJL_TEXT_MODEL_PATH"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: Path.of("data/models/djl/text"),
                djlEmotionalModelPath = env["OPENEDEN_DJL_EMOTIONAL_MODEL_PATH"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: Path.of("data/models/djl/emotional"),
                djlEngineName = env.valueOrDefault("OPENEDEN_DJL_ENGINE", "PyTorch"),
                djlModelName = env.valueOrDefault("OPENEDEN_DJL_MODEL_NAME", "model"),
            )
        }

        private fun Map<String, String>.valueOrDefault(key: String, default: String): String =
            get(key)?.takeIf { it.isNotBlank() } ?: default
    }
}

data class LlmProviderConfig(
    val provider: String,
    val model: String,
    val openAiBaseUrl: String,
    val openAiApiKey: String?,
)
