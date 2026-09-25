package messaging.store

import messaging.domain.ConversationEvent
import messaging.domain.ConversationId

class ConcurrencyConflict(val expected: Int, val actual: Int) :
    RuntimeException("expected version $expected but stream was at $actual")

data class Stream(
    val events: List<ConversationEvent>,
    val version: Int,
)

interface EventStore {
    fun read(id: ConversationId): Stream

    fun append(id: ConversationId, expectedVersion: Int, events: List<ConversationEvent>)

    fun allEvents(): List<ConversationEvent>

    fun subscribe(listener: (ConversationEvent) -> Unit)
}
