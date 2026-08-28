package io.openeden.relationship

data class RelationshipCorrection(
    val event: RelationshipEvent,
) {
    init {
        require(event.supersedesEventId != null) { "Correction events must supersede an earlier event" }
    }
}
