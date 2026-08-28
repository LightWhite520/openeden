package io.openeden.identity

@JvmInline
value class CanonicalSubjectId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Canonical subject ID must not be blank" }
    }
}
