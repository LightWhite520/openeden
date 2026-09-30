package io.openeden.server.auth

/** Only fixed, operator-safe messages may cross the CLI/API boundary. */
class ChatGptAuthException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String, val retryable: Boolean = false) {
        REAUTHORIZATION_REQUIRED("ChatGPT sign-in is required; run :server:chatgptAuth --args=login"),
        PERMISSION_REQUIRED("ChatGPT plan usage permission was not granted; authorize plan usage"),
        INVALID_CLIENT("ChatGPT client registration is invalid; check the selected registration"),
        TEMPORARILY_UNAVAILABLE("ChatGPT authentication endpoint is unavailable; retry later", true),
        IDENTITY_VALIDATION_FAILED("ChatGPT identity validation failed; credentials have not been activated"),
        INVALID_TOKEN_RESPONSE("ChatGPT token response is invalid; credentials have not been activated"),
        CREDENTIAL_STORAGE_FAILED("Unable to save protected ChatGPT credentials; check local storage", true),
    }

    val code: String get() = "CHATGPT_${reason.name}"
}
