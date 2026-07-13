package io.openeden.relationship

interface UserAffectAnalyzer {
    suspend fun analyze(text: String): UserAffectState
}
