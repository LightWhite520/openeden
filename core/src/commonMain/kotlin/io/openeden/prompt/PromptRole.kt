package io.openeden.prompt

enum class PromptRole(val apiValue: String) {
    SYSTEM("system"),
    DEVELOPER("developer"),
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromApiValue(value: String): PromptRole = entries.firstOrNull { it.apiValue == value }
            ?: throw IllegalArgumentException("Unsupported prompt role: $value")
    }
}
