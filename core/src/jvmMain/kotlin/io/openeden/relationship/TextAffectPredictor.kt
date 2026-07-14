package io.openeden.relationship

interface TextAffectPredictor : AutoCloseable {
    fun predict(text: String): FloatArray
}
