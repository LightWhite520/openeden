package io.openeden.persona

import io.openeden.relationship.RelationshipPhase

data class PersonaFewShot(
    val phase: RelationshipPhase,
    val messages: List<PersonaExampleMessage>,
) {
    init {
        require(messages.isNotEmpty()) { "Persona few-shot messages must not be empty" }
    }
}
