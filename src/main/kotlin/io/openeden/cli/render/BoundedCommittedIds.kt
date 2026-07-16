package io.openeden.cli.render

internal class BoundedCommittedIds(
    private val capacity: Int,
) {
    private val ids = LinkedHashMap<String, Unit>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    fun mark(id: String): Boolean {
        if (ids.put(id, Unit) != null) return false
        if (ids.size > capacity) {
            val oldest = ids.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}
