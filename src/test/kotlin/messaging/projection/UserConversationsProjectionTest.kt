package messaging.projection

import messaging.domain.ConversationId
import messaging.domain.ConversationStarted
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class UserConversationsProjectionTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `a user with no conversations gets an empty list`() {
        assertEquals(emptyList(), UserConversationsProjection().conversationsOf("alice"))
    }

    @Test
    fun `lists every conversation a user participates in`() {
        val projection = UserConversationsProjection()

        projection.on(ConversationStarted(ConversationId("c1"), setOf("alice", "bob"), now))
        projection.on(ConversationStarted(ConversationId("c2"), setOf("alice", "carol"), now))
        projection.on(ConversationStarted(ConversationId("c3"), setOf("bob", "carol"), now))

        assertEquals(listOf("c1", "c2"), projection.conversationsOf("alice"))
        assertEquals(listOf("c1", "c3"), projection.conversationsOf("bob"))
    }
}
