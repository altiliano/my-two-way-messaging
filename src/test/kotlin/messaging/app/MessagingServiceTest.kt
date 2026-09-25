package messaging.app

import messaging.domain.ConversationId
import messaging.domain.MessageId
import messaging.domain.PostMessage
import messaging.domain.StartConversation
import messaging.store.ConcurrencyConflict
import messaging.store.InMemoryEventStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessagingServiceTest {

    private val id = ConversationId("c1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `handling a command appends events and returns the new version`() {
        val store = InMemoryEventStore()
        val service = MessagingService(store)

        val afterStart = service.handle(
            StartConversation(id, setOf("alice", "bob"), now),
            expectedVersion = 0,
        )
        val afterPost = service.handle(
            PostMessage(id, MessageId("m1"), "alice", "hi", now),
            expectedVersion = 1,
        )

        assertEquals(1, afterStart)
        assertEquals(2, afterPost)
        assertEquals(2, store.read(id).version)
    }

    @Test
    fun `handling with a stale expected version conflicts`() {
        val store = InMemoryEventStore()
        val service = MessagingService(store)
        service.handle(StartConversation(id, setOf("alice", "bob"), now), 0)

        assertFailsWith<ConcurrencyConflict> {
            service.handle(PostMessage(id, MessageId("m1"), "alice", "hi", now), expectedVersion = 0)
        }
    }
}
