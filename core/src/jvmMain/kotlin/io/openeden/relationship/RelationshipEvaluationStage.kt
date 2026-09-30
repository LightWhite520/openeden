package io.openeden.relationship

/** Safe diagnostic labels; never derived from provider text or credentials. */
enum class RelationshipEvaluationStage {
    MODEL_SELECTION,
    AUTHORIZATION,
    REQUEST,
    HTTP_STATUS,
    CONTENT_TYPE,
    RESPONSE_BODY,
    SSE_COMPLETION,
    EVALUATION_PARSE,
}
