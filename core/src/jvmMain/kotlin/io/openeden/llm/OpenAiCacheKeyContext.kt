package io.openeden.llm

data class OpenAiCacheKeyContext(
    val providerPolicyRevision: String,
    val systemSchemaRevision: String,
    val personaRevision: String,
    val dialogueNamespace: String,
) {
    init {
        require(providerPolicyRevision.isNotBlank()) { "providerPolicyRevision must not be blank" }
        require(systemSchemaRevision.isNotBlank()) { "systemSchemaRevision must not be blank" }
        require(personaRevision.isNotBlank()) { "personaRevision must not be blank" }
        require(dialogueNamespace.isNotBlank()) { "dialogueNamespace must not be blank" }
    }

    companion object {
        val Default = OpenAiCacheKeyContext(
            providerPolicyRevision = "responses-v1",
            systemSchemaRevision = "openeden-output-schema-v1",
            personaRevision = "persona-v1",
            dialogueNamespace = "openeden-dialogue-v1",
        )
    }
}
