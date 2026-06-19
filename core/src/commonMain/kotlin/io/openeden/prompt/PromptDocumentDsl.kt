package io.openeden.prompt

@DslMarker
annotation class PromptDsl

fun promptDocument(block: PromptObjectBuilder.() -> Unit): PromptDocument =
    PromptDocument(PromptObjectBuilder().apply(block).build())

data class PromptDocument(
    val root: PromptObject,
)

data class PromptObject(
    val fields: List<PromptField>,
) : PromptValue

data class PromptField(
    val name: String,
    val value: PromptValue,
)

data class PromptArray(
    val values: List<PromptValue>,
) : PromptValue

data class PromptScalar(
    val value: Any?,
) : PromptValue

sealed interface PromptValue

@PromptDsl
class PromptObjectBuilder {
    private val fields = mutableListOf<PromptField>()

    operator fun String.invoke(block: PromptObjectBuilder.() -> Unit) {
        fields += PromptField(this, PromptObjectBuilder().apply(block).build())
    }

    infix fun String.to(value: String?) {
        field(this, scalar(value))
    }

    infix fun String.to(value: Number?) {
        field(this, scalar(value))
    }

    infix fun String.to(value: Boolean?) {
        field(this, scalar(value))
    }

    infix fun String.to(value: PromptValue) {
        field(this, value)
    }

    fun field(name: String, value: PromptValue) {
        fields += PromptField(name, value)
    }

    fun obj(block: PromptObjectBuilder.() -> Unit): PromptObject =
        PromptObjectBuilder().apply(block).build()

    fun array(vararg values: Any?): PromptArray =
        PromptArray(values.map(::promptValueOf))

    fun array(values: Iterable<Any?>): PromptArray =
        PromptArray(values.map(::promptValueOf))

    fun build(): PromptObject = PromptObject(fields.toList())
}

fun promptValueOf(value: Any?): PromptValue = when (value) {
    is PromptValue -> value
    is Iterable<*> -> PromptArray(value.map(::promptValueOf))
    is Array<*> -> PromptArray(value.map(::promptValueOf))
    else -> PromptScalar(value)
}

fun scalar(value: Any?): PromptScalar = PromptScalar(value)
