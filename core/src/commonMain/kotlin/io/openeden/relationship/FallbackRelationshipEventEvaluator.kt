package io.openeden.relationship

import kotlinx.coroutines.CancellationException

class FallbackRelationshipEventEvaluator(
    private val primary: RelationshipEventEvaluator,
    private val fallback: RelationshipEventEvaluator,
    private val onPrimaryFailure: (Exception) -> Unit = {},
) : RelationshipEventEvaluator {
    override suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation = try {
        primary.evaluate(turn)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        try {
            onPrimaryFailure(failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Observability must not prevent the configured fallback from running.
        }
        fallback.evaluate(turn)
    }
}
