package pulse.core

/**
 * Bounded ring buffer of pending events. Stability over completeness: when full, the oldest event
 * is dropped and counted, never unbounded growth. Reactor/collector-thread use only (the SDK runs
 * its pipeline on one dispatcher), so no locking.
 */
class EventBuffer(private val capacity: Int) {
    private val items = ArrayDeque<Event>()
    var dropped: Long = 0L; private set

    val size: Int get() = items.size

    fun add(event: Event) {
        if (items.size >= capacity) { items.removeFirst(); dropped++ }
        items.addLast(event)
    }

    /** Remove and return up to [max] oldest events for a batch. */
    fun drain(max: Int): List<Event> {
        if (items.isEmpty()) return emptyList()
        val n = minOf(max, items.size)
        val out = ArrayList<Event>(n)
        repeat(n) { out.add(items.removeFirst()) }
        return out
    }

    /** Put a failed batch back at the front, preserving order, for retry. */
    fun requeueFront(events: List<Event>) {
        for (i in events.indices.reversed()) {
            if (items.size >= capacity) { items.removeLast(); dropped++ }
            items.addFirst(events[i])
        }
    }
}
