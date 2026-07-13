package io.openeden.codebook

import ai.djl.Model
import ai.djl.inference.Predictor
import ai.djl.ndarray.NDList
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import java.nio.file.Path

interface DjlFloatPredictor : AutoCloseable {
    fun predict(input: FloatArray): FloatArray

    companion object {
        fun fromModelPath(
            modelPath: Path,
            modelName: String,
            engineName: String,
        ): DjlFloatPredictor {
            val model = Model.newInstance(modelName, engineName)
            model.load(modelPath, modelName)
            return DjlModelFloatPredictor(model.newPredictor(FloatArrayTranslator()), model)
        }
    }
}

private class DjlModelFloatPredictor(
    private val predictor: Predictor<FloatArray, FloatArray>,
    private val model: Model,
) : DjlFloatPredictor {
    override fun predict(input: FloatArray): FloatArray = predictor.predict(input)
    override fun close() {
        predictor.close()
        model.close()
    }
}

private class FloatArrayTranslator : Translator<FloatArray, FloatArray> {
    override fun processInput(context: TranslatorContext, input: FloatArray): NDList =
        NDList(context.ndManager.create(input))

    override fun processOutput(context: TranslatorContext, list: NDList): FloatArray =
        list.singletonOrThrow().toFloatArray()
}
