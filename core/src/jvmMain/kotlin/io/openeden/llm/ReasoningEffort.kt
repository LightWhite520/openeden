package io.openeden.llm

enum class ReasoningEffort(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun parse(value: String): ReasoningEffort = entries.firstOrNull { it.value == value.trim().lowercase() }
            ?: error("Unsupported reasoning effort '$value'; expected low, medium, or high")
    }
}
