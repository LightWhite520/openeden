package io.openeden.prompt

class PromptRenderer(
    private val indent: String = "  ",
) {
    fun render(document: PromptDocument): String = renderObject(document.root, depth = 0)

    fun renderField(document: PromptDocument, fieldName: String): String {
        val field = document.root.fields.firstOrNull { it.name == fieldName }
            ?: return "{}"
        return renderValue(field.value, depth = 0)
    }

    private fun renderValue(value: PromptValue, depth: Int): String = when (value) {
        is PromptObject -> renderObject(value, depth)
        is PromptArray -> renderArray(value, depth)
        is PromptScalar -> renderScalar(value.value)
    }

    private fun renderObject(value: PromptObject, depth: Int): String {
        if (value.fields.isEmpty()) return "{}"
        val nextDepth = depth + 1
        return buildString {
            appendLine("{")
            value.fields.forEachIndexed { index, field ->
                append(indent.repeat(nextDepth))
                append(renderString(field.name))
                append(": ")
                append(renderValue(field.value, nextDepth))
                if (index != value.fields.lastIndex) append(",")
                appendLine()
            }
            append(indent.repeat(depth))
            append("}")
        }
    }

    private fun renderArray(value: PromptArray, depth: Int): String {
        if (value.values.isEmpty()) return "[]"
        val nextDepth = depth + 1
        return buildString {
            appendLine("[")
            value.values.forEachIndexed { index, item ->
                append(indent.repeat(nextDepth))
                append(renderValue(item, nextDepth))
                if (index != value.values.lastIndex) append(",")
                appendLine()
            }
            append(indent.repeat(depth))
            append("]")
        }
    }

    private fun renderScalar(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> renderString(value.toString())
    }

    private fun renderString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
}
