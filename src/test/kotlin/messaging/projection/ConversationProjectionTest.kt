package messaging.projection

import messaging.domain.ConversationId
import messaging.domain.ConversationStarted
import messaging.domain.MessageId
import messaging.domain.MessagePosted
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationProjectionTest {

    private val id = ConversationId("c1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `unknown conversation has no view`() {
        assertNull(ConversationProjection().get(id))
    }

    @Test
    fun `builds a view from started and posted events`() {
        val projection = ConversationProjection()

        projection.on(ConversationStarted(id, setOf("alice", "bob"), now))
        projection.on(MessagePosted(id, MessageId("m1"), "alice", "hi bob", now))
        projection.on(MessagePosted(id, MessageId("m2"), "bob", "hi alice", now))

        assertEquals(
            ConversationView(
                id = "c1",
                participants = setOf("alice", "bob"),
                version = 3,
                messages = listOf(
                    MessageView("m1", "alice", "hi bob", now),
                    MessageView("m2", "bob", "hi alice", now),
                ),
            ),
            projection.get(id),
        )
    }
}
