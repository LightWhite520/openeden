package io.openeden.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StructuredStreamException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class StrictOutputStreamDecoder(
    private val json: Json = Json,
) {
    private val raw = StringBuilder()
    private var emittedResponseChars = 0
    private var failure: StructuredStreamException? = null

    fun accept(chunk: String): List<String> {
        raw.append(chunk)
        if (failure != null) return emptyList()
        return try {
            val responseStart = scanRequiredPrefix(raw) ?: return emptyList()
            val decoded = decodeResponse(raw, responseStart)
            if (decoded.text.length <= emittedResponseChars) return emptyList()
            listOf(decoded.text.substring(emittedResponseChars).also { emittedResponseChars = decoded.text.length })
        } catch (error: StructuredStreamException) {
            failure = error
            emptyList()
        }
    }

    fun finish(): LlmOutput {
        failure?.let { throw it }
        val source = raw.toString()
        val root = try {
            json.parseToJsonElement(source).jsonObject
        } catch (error: Throwable) {
            throw StructuredStreamException("Structured output is not valid JSON", error)
        }
        if (root.keys != ROOT_KEYS.toSet()) {
            throw StructuredStreamException("Structured output must contain internal_logic, vector_delta, and response")
        }
        val vector = root.getValue("vector_delta").jsonObject
        if (vector.keys != VECTOR_KEYS.toSet()) {
            throw StructuredStreamException("vector_delta must contain the eight required fields")
        }
        val output = try {
            LlmOutput(
                internalLogic = root.getValue("internal_logic").jsonPrimitive.content,
                vectorDelta = VECTOR_KEYS.associateWith { vector.getValue(it).jsonPrimitive.float },
                response = root.getValue("response").jsonPrimitive.content,
            )
        } catch (error: Throwable) {
            throw StructuredStreamException("Structured output contains invalid field values", error)
        }
        val validation = LlmOutputValidator.validate(output)
        if (!validation.isValid) {
            throw StructuredStreamException(validation.errors.joinToString("; "))
        }
        return output
    }

    private fun scanRequiredPrefix(source: CharSequence): Int? {
        var index = skipWhitespace(source, 0)
        index = expect(source, index, '{') ?: return null

        val internalKey = readString(source, skipWhitespace(source, index)) ?: return null
        if (internalKey.value != "internal_logic") return null
        index = expect(source, skipWhitespace(source, internalKey.end), ':') ?: return null
        val internalValue = readString(source, skipWhitespace(source, index)) ?: return null
        index = expect(source, skipWhitespace(source, internalValue.end), ',') ?: return null

        val vectorKey = readString(source, skipWhitespace(source, index)) ?: return null
        if (vectorKey.value != "vector_delta") return null
        index = expect(source, skipWhitespace(source, vectorKey.end), ':') ?: return null
        index = skipWhitespace(source, index)
        val vectorEnd = readObjectEnd(source, index) ?: return null
        validateVectorPrefix(source.subSequence(index, vectorEnd).toString())
        index = expect(source, skipWhitespace(source, vectorEnd), ',') ?: return null

        val responseKey = readString(source, skipWhitespace(source, index)) ?: return null
        if (responseKey.value != "response") return null
        index = expect(source, skipWhitespace(source, responseKey.end), ':') ?: return null
        index = skipWhitespace(source, index)
        if (index >= source.length) return null
        if (source[index] != '"') invalid("response must be a JSON string")
        return index + 1
    }

    private fun validateVectorPrefix(source: String) {
        val vector = try {
            json.parseToJsonElement(source) as? JsonObject
                ?: invalid("vector_delta must be an object")
        } catch (error: StructuredStreamException) {
            throw error
        } catch (error: Throwable) {
            throw StructuredStreamException("vector_delta is not valid JSON", error)
        }
        if (vector.keys != VECTOR_KEYS.toSet()) {
            invalid("vector_delta must contain the eight required fields")
        }
        try {
            VECTOR_KEYS.forEach { vector.getValue(it).jsonPrimitive.float }
        } catch (error: Throwable) {
            throw StructuredStreamException("vector_delta values must be numbers", error)
        }
    }

    private fun decodeResponse(source: CharSequence, start: Int): DecodedResponse {
        val decoded = StringBuilder()
        var index = start
        while (index < source.length) {
            val character = source[index]
            when {
                character == '"' -> return DecodedResponse(decoded.toString(), complete = true)
                character == '\\' -> {
                    if (index + 1 >= source.length) return DecodedResponse(decoded.toString(), complete = false)
                    when (val escaped = source[index + 1]) {
                        '"', '\\', '/' -> {
                            decoded.append(escaped)
                            index += 2
                        }
                        'b' -> { decoded.append('\b'); index += 2 }
                        'f' -> { decoded.append('\u000C'); index += 2 }
                        'n' -> { decoded.append('\n'); index += 2 }
                        'r' -> { decoded.append('\r'); index += 2 }
                        't' -> { decoded.append('\t'); index += 2 }
                        'u' -> {
                            val first = readUnicodeEscape(source, index) ?: return DecodedResponse(decoded.toString(), false)
                            if (first.first.isHighSurrogate()) {
                                val secondIndex = index + 6
                                if (secondIndex + 1 >= source.length) return DecodedResponse(decoded.toString(), false)
                                if (source[secondIndex] != '\\' || source[secondIndex + 1] != 'u') {
                                    invalid("High surrogate must be followed by a low surrogate escape")
                                }
                                val second = readUnicodeEscape(source, secondIndex)
                                    ?: return DecodedResponse(decoded.toString(), false)
                                if (!second.first.isLowSurrogate()) invalid("Invalid low surrogate escape")
                                decoded.append(first.first).append(second.first)
                                index = second.second
                            } else {
                                if (first.first.isLowSurrogate()) invalid("Unexpected low surrogate escape")
                                decoded.append(first.first)
                                index = first.second
                            }
                        }
                        else -> invalid("Invalid JSON escape: \\$escaped")
                    }
                }
                character.code < 0x20 -> invalid("Control character in response string")
                character.isHighSurrogate() -> {
                    if (index + 1 >= source.length) return DecodedResponse(decoded.toString(), false)
                    val low = source[index + 1]
                    if (!low.isLowSurrogate()) invalid("Invalid response surrogate pair")
                    decoded.append(character).append(low)
                    index += 2
                }
                character.isLowSurrogate() -> invalid("Unexpected low surrogate in response")
                else -> {
                    decoded.append(character)
                    index += 1
                }
            }
        }
        return DecodedResponse(decoded.toString(), complete = false)
    }

    private fun readUnicodeEscape(source: CharSequence, slashIndex: Int): Pair<Char, Int>? {
        if (slashIndex + 6 > source.length) return null
        val value = source.subSequence(slashIndex + 2, slashIndex + 6).toString().toIntOrNull(16)
            ?: invalid("Invalid Unicode escape")
        return value.toChar() to (slashIndex + 6)
    }

    private fun readString(source: CharSequence, start: Int): StringToken? {
        if (start >= source.length) return null
        if (source[start] != '"') invalid("Expected JSON string")
        var index = start + 1
        var escaped = false
        while (index < source.length) {
            val character = source[index]
            if (escaped) {
                if (character == 'u') {
                    if (index + 5 > source.length) return null
                    source.subSequence(index + 1, index + 5).toString().toIntOrNull(16)
                        ?: invalid("Invalid Unicode escape")
                    index += 5
                } else {
                    if (character !in "\"\\/bfnrt") invalid("Invalid JSON escape")
                    index += 1
                }
                escaped = false
            } else when {
                character == '\\' -> { escaped = true; index += 1 }
                character == '"' -> {
                    val end = index + 1
                    val value = try {
                        json.decodeFromString<String>(source.subSequence(start, end).toString())
                    } catch (error: Throwable) {
                        throw StructuredStreamException("Invalid JSON string", error)
                    }
                    return StringToken(value, end)
                }
                character.code < 0x20 -> invalid("Control character in JSON string")
                else -> index += 1
            }
        }
        return null
    }

    private fun readObjectEnd(source: CharSequence, start: Int): Int? {
        if (start >= source.length) return null
        if (source[start] != '{') invalid("Expected vector_delta object")
        var depth = 1
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '"' -> index = readString(source, index)?.end ?: return null
                '{' -> { depth += 1; index += 1 }
                '}' -> {
                    depth -= 1
                    index += 1
                    if (depth == 0) return index
                }
                else -> index += 1
            }
        }
        return null
    }

    private fun expect(source: CharSequence, index: Int, expected: Char): Int? {
        if (index >= source.length) return null
        if (source[index] != expected) invalid("Expected '$expected'")
        return index + 1
    }

    private fun skipWhitespace(source: CharSequence, start: Int): Int {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index += 1
        return index
    }

    private fun invalid(message: String): Nothing = throw StructuredStreamException(message)

    private data class StringToken(val value: String, val end: Int)

    private data class DecodedResponse(val text: String, val complete: Boolean)

    private companion object {
        val ROOT_KEYS = listOf("internal_logic", "vector_delta", "response")
        val VECTOR_KEYS = listOf("L", "P", "E", "S", "tau", "V", "M", "F")
    }
}
