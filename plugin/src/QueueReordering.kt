package automix

internal data class QueueOrder(val ids: List<Int>, val currentIndex: Int, val shuffled: Boolean) {
    val fixedPrefix get() = if (currentIndex in ids.indices) currentIndex + 1 else 0
}

internal data class QueueReorderOutcome(val complete: Boolean, val moves: Int)

/** Move upcoming items through the host so the playing item and its native queue identity survive. */
internal suspend fun applyUpcomingQueueOrder(
    initial: QueueOrder,
    target: List<Int>,
    current: () -> QueueOrder?,
    move: suspend (QueueOrder, Int, Int) -> QueueOrder?
): QueueReorderOutcome {
    val fixed = initial.fixedPrefix
    require(target.size == initial.ids.size && target.toSet().size == target.size &&
        target.toSet() == initial.ids.toSet() && target.take(fixed) == initial.ids.take(fixed))
    if (initial.shuffled || current() != initial) return QueueReorderOutcome(false, 0)

    var state = initial
    var moved = 0
    for (destination in fixed until target.size) {
        if (state.ids[destination] == target[destination]) continue
        if (current() != state) return QueueReorderOutcome(false, moved)
        val source = state.ids.indexOf(target[destination])
        val expected = state.copy(ids = state.ids.toMutableList().apply { add(destination, removeAt(source)) })
        val observed = move(state, source, destination)
        if (observed != expected) return QueueReorderOutcome(false, moved)
        state = expected
        moved++
    }
    return QueueReorderOutcome(current() == state, moved)
}
