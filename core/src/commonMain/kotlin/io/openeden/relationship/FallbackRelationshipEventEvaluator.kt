package io.openeden.relationship

import kotlinx.coroutines.CancellationException

class FallbackRelationshipEventEvaluator(
    private val primary: RelationshipEventEvaluator,
    private val fallback: RelationshipEventEvaluator,
) : RelationshipEventEvaluator {
    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation = try {
        primary.evaluate(turn)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback.evaluate(turn)
    }
}
