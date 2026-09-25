package messaging.store

import messaging.domain.ConversationEvent
import messaging.domain.ConversationId
import messaging.domain.ConversationStarted
import messaging.domain.MessageId
import messaging.domain.MessagePosted
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryEventStoreTest {

    private val id = ConversationId("c1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val started = ConversationStarted(id, setOf("alice", "bob"), now)
    private val message = MessagePosted(id, MessageId("m1"), "alice", "hi", now)

    @Test
    fun `reading an unknown stream yields no events at version zero`() {
        val store = InMemoryEventStore()

        assertEquals(Stream(emptyList(), 0), store.read(id))
    }

    @Test
    fun `appended events are read back with an advancing version`() {
        val store = InMemoryEventStore()

        store.append(id, expectedVersion = 0, events = listOf(started))
        store.append(id, expectedVersion = 1, events = listOf(message))

        assertEquals(Stream(listOf(started, message), 2), store.read(id))
    }

    @Test
    fun `appending with a stale expected version is rejected`() {
        val store = InMemoryEventStore()
        store.append(id, expectedVersion = 0, events = listOf(started))

        val conflict = assertFailsWith<ConcurrencyConflict> {
            store.append(id, expectedVersion = 0, events = listOf(message))
        }

        assertEquals(0, conflict.expected)
        assertEquals(1, conflict.actual)
        assertEquals(1, store.read(id).version)
    }

    @Test
    fun `all events are exposed in append order across streams`() {
        val store = InMemoryEventStore()
        val other = ConversationId("c2")
        val otherStarted = ConversationStarted(other, setOf("carol", "dan"), now)

        store.append(id, 0, listOf(started))
        store.append(other, 0, listOf(otherStarted))

        assertEquals(listOf<ConversationEvent>(started, otherStarted), store.allEvents())
    }

    @Test
    fun `subscribers are notified of appended events`() {
        val store = InMemoryEventStore()
        val received = mutableListOf<ConversationEvent>()
        store.subscribe { received.add(it) }

        store.append(id, 0, listOf(started, message))

        assertEquals(listOf(started, message), received)
    }
}
