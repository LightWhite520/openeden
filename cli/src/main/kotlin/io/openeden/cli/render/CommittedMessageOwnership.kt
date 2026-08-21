package io.openeden.cli.render

internal class CommittedMessageOwnership(
    tombstoneCapacity: Int,
) {
    private var visibleCommittedIds = linkedSetOf<String>()
    private val recentlyRemovedIds = RemovedIdTombstones(tombstoneCapacity)

    fun newIds(currentIdsInOrder: List<String>): List<String> {
        val currentIds = LinkedHashSet<String>(currentIdsInOrder.size)
        currentIds.addAll(currentIdsInOrder)
        visibleCommittedIds.filterNot(currentIds::contains).forEach(recentlyRemovedIds::add)

        val newIds = currentIds.filter { id ->
            id !in visibleCommittedIds && !recentlyRemovedIds.consume(id)
        }
        visibleCommittedIds = currentIds
        return newIds
    }
}

private class RemovedIdTombstones(
    private val capacity: Int,
) {
    private val ids = LinkedHashMap<String, Unit>(capacity, 0.75f, true)

    init {
        require(capacity > 0)
    }

    fun add(id: String) {
        ids[id] = Unit
        if (ids.size > capacity) {
            val oldest = ids.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    fun consume(id: String): Boolean = ids.remove(id) != null
}
