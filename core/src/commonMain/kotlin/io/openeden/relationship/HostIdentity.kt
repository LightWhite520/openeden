package io.openeden.relationship

data class HostIdentity(
    val platform: String,
    val userId: String,
) {
    init {
        require(platform.isNotBlank()) { "Host platform must not be blank" }
        require(userId.isNotBlank()) { "Host user ID must not be blank" }
    }
}
