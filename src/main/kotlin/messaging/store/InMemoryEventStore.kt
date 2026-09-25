package messaging.store

import messaging.domain.ConversationEvent
import messaging.domain.ConversationId

class InMemoryEventStore : EventStore {

    private val lock = Any()
    private val streams = mutableMapOf<ConversationId, MutableList<ConversationEvent>>()
    private val log = mutableListOf<ConversationEvent>()
    private val subscribers = mutableListOf<(ConversationEvent) -> Unit>()

    override fun read(id: ConversationId): Stream = synchronized(lock) {
        val events = streams[id]?.toList() ?: emptyList()
        Stream(events, events.size)
    }

    override fun append(id: ConversationId, expectedVersion: Int, events: List<ConversationEvent>) {
        val appended = synchronized(lock) {
            val stream = streams.getOrPut(id) { mutableListOf() }
            if (stream.size != expectedVersion) {
                throw ConcurrencyConflict(expectedVersion, stream.size)
            }
            stream.addAll(events)
            log.addAll(events)
            events
        }
        appended.forEach { event -> subscribers.forEach { it(event) } }
    }

    override fun allEvents(): List<ConversationEvent> = synchronized(lock) { log.toList() }

    override fun subscribe(listener: (ConversationEvent) -> Unit) {
        synchronized(lock) { subscribers.add(listener) }
    }
}
