package io.openeden.nn

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

@Serializable
data class LocalMlpSpec(
    val inputSize: Int,
    val layers: List<LocalDenseLayerSpec>,
) {
    init {
        require(inputSize > 0) { "inputSize must be positive" }
        require(layers.isNotEmpty()) { "layers must not be empty" }
    }
}

@Serializable
data class LocalDenseLayerSpec(
    val outputSize: Int,
    val weights: List<List<Float>>,
    val biases: List<Float>,
    val activation: LocalActivation = LocalActivation.LINEAR,
) {
    init {
        require(outputSize > 0) { "outputSize must be positive" }
        require(weights.size == outputSize) { "weights row count must equal outputSize" }
        require(biases.size == outputSize) { "biases size must equal outputSize" }
        require(weights.all { it.isNotEmpty() }) { "weight rows must not be empty" }
    }
}

@Serializable
enum class LocalActivation {
    LINEAR,
    RELU,
    TANH,
    SIGMOID,
}

class LocalMlp(
    private val spec: LocalMlpSpec,
) {
    fun forward(input: List<Float>): List<Float> {
        require(input.size == spec.inputSize) {
            "Expected ${spec.inputSize} inputs, got ${input.size}"
        }
        var current = input
        for (layer in spec.layers) {
            require(layer.weights.all { it.size == current.size }) {
                "Layer expected ${layer.weights.first().size} inputs, got ${current.size}"
            }
            current = layer.forward(current)
        }
        return current
    }

    private fun LocalDenseLayerSpec.forward(input: List<Float>): List<Float> =
        List(outputSize) { row ->
            val weighted = weights[row].foldIndexed(biases[row]) { index, acc, value ->
                acc + value * input[index]
            }
            activation.apply(weighted)
        }
}

fun LocalActivation.apply(value: Float): Float = when (this) {
    LocalActivation.LINEAR -> value
    LocalActivation.RELU -> max(0.0f, value)
    LocalActivation.TANH -> tanh(value)
    LocalActivation.SIGMOID -> 1.0f / (1.0f + exp(-value))
}

fun normalizedCosine(left: List<Float>, right: List<Float>): Float {
    val size = minOf(left.size, right.size)
    if (size == 0) return 0.0f
    var dot = 0.0f
    var leftNorm = 0.0f
    var rightNorm = 0.0f
    for (index in 0 until size) {
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    val denominator = sqrt(leftNorm) * sqrt(rightNorm)
    return if (denominator == 0.0f) 0.0f else dot / denominator
}

fun l2Normalize(values: List<Float>): List<Float> {
    var norm = 0.0f
    for (value in values) norm += value * value
    val denominator = sqrt(norm)
    return if (denominator == 0.0f) values else values.map { it / denominator }
}

private fun tanh(value: Float): Float {
    val positive = exp(value)
    val negative = exp(-value)
    return (positive - negative) / (positive + negative)
}
