package io.openeden.persona

data class PersonaOutputPolicy(
    val prohibitedPublicPhrases: Set<String> = emptySet(),
    val prohibitedPublicPatterns: Set<String> = emptySet(),
    val maximumRepeatedOpening: Int = Int.MAX_VALUE,
    val minimumRepeatedOpeningLength: Int = 1,
) {
    init {
        require(prohibitedPublicPhrases.none(String::isBlank)) {
            "Prohibited public phrases must not be blank"
        }
        require(prohibitedPublicPatterns.none(String::isBlank)) {
            "Prohibited public patterns must not be blank"
        }
        require(maximumRepeatedOpening >= 1) {
            "Maximum repeated opening must be at least one"
        }
        require(minimumRepeatedOpeningLength >= 1) {
            "Minimum repeated opening length must be at least one"
        }
    }
}
