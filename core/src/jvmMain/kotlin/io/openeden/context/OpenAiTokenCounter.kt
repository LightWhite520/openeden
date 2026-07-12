package io.openeden.context

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import io.openeden.prompt.BuiltPrompt

/** Counts the actual prompt payload with the tokenizer used by current OpenAI 200K models. */
class OpenAiTokenCounter(
    private val encoding: Encoding = Encodings.newDefaultEncodingRegistry()
        .getEncoding(EncodingType.O200K_BASE),
) {
    fun count(prompt: BuiltPrompt): Int = encoding.countTokens(
        listOf(prompt.systemText, prompt.personaText, prompt.userText).joinToString("\n\n"),
    )
}
