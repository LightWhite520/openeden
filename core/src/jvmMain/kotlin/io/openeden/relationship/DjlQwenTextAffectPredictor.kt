package io.openeden.relationship

import ai.djl.Model
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.ndarray.NDList
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class DjlQwenTextAffectPredictor private constructor(
    private val predictor: Predictor<String, FloatArray>,
    private val model: Model,
    private val tokenizer: HuggingFaceTokenizer,
    private val maxSequenceLength: Int,
) : TextAffectPredictor {
    override fun predict(text: String): FloatArray = predictor.predict(text)

    override fun close() {
        predictor.close()
        model.close()
        tokenizer.close()
    }

    companion object {
        fun fromBundle(bundlePath: Path, engineName: String = "PyTorch"): DjlQwenTextAffectPredictor {
            val metadataPath = bundlePath.resolve("metadata.json")
            val modelPath = bundlePath.resolve("model.pt")
            val tokenizerPath = bundlePath.resolve("tokenizer.json")
            require(Files.isRegularFile(metadataPath)) { "Qwen affect metadata is missing: $metadataPath" }
            require(Files.isRegularFile(modelPath)) { "Qwen affect model is missing: $modelPath" }
            require(Files.isRegularFile(tokenizerPath)) { "Qwen affect tokenizer is missing: $tokenizerPath" }

            val metadata = Json.parseToJsonElement(Files.readString(metadataPath)).jsonObject
            require(metadata["schemaVersion"]?.jsonPrimitive?.content?.toInt() == 2) { "Unsupported Qwen affect schema version" }
            require(metadata["baseModel"]?.jsonPrimitive?.content == "Qwen/Qwen3-Embedding-0.6B") { "Unexpected Qwen affect base model" }
            require(metadata["precision"]?.jsonPrimitive?.content == "bfloat16") { "Unexpected Qwen affect precision" }
            require(metadata["embeddingDimension"]?.jsonPrimitive?.content?.toInt() == 1024) { "Unexpected Qwen affect embedding dimension" }
            val outputs = metadata["outputs"]?.jsonArray?.map { it.jsonPrimitive.content }
            require(outputs == listOf("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")) {
                "Unexpected Qwen affect outputs"
            }
            val maxSequenceLength = metadata["maxSequenceLength"]?.jsonPrimitive?.content?.toInt()
                ?: error("Qwen affect maxSequenceLength is missing")
            require(maxSequenceLength > 0) { "Qwen affect maxSequenceLength must be positive" }

            val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath)
            val model = Model.newInstance("model", engineName)
            try {
                model.load(bundlePath, "model")
                val predictor = model.newPredictor(QwenAffectTranslator(tokenizer, maxSequenceLength))
                return DjlQwenTextAffectPredictor(predictor, model, tokenizer, maxSequenceLength)
            } catch (failure: Throwable) {
                tokenizer.close()
                model.close()
                throw failure
            }
        }
    }
}

private class QwenAffectTranslator(
    private val tokenizer: HuggingFaceTokenizer,
    private val maxSequenceLength: Int,
) : Translator<String, FloatArray> {
    override fun processInput(context: TranslatorContext, input: String): NDList {
        val encoded = tokenizer.encode(input)
        val ids = LongArray(maxSequenceLength)
        val mask = LongArray(maxSequenceLength)
        val sourceIds = encoded.ids
        val sourceMask = encoded.attentionMask
        val length = minOf(maxSequenceLength, sourceIds.size)
        sourceIds.copyInto(ids, endIndex = length)
        sourceMask.copyInto(mask, endIndex = length)
        return NDList(context.ndManager.create(ids), context.ndManager.create(mask))
    }

    override fun processOutput(context: TranslatorContext, list: NDList): FloatArray {
        val output = list.singletonOrThrow().toFloatArray()
        require(output.size == 6 && output.all(Float::isFinite)) {
            "Qwen affect output must contain six finite values"
        }
        return output
    }
}
