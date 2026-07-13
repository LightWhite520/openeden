package io.openeden.relationship

fun relationshipKey(sessionId: String, userId: String): String = "$sessionId\u0000$userId"
