package io.openeden.persona

data class PersonaExampleMessage(
    val role: PersonaExampleRole,
    val content: String,
) {
    init {
        require(content.isNotBlank()) { "Persona example content must not be blank" }
    }
}
