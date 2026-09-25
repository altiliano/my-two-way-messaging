package messaging.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationTest {

    private val id = ConversationId("c1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val participants = setOf("alice", "bob")

    private fun stateAfter(vararg events: ConversationEvent) =
        initialConversationState.replay(events.toList())

    @Test
    fun `starting a new conversation emits ConversationStarted`() {
        val events = decide(
            StartConversation(id, participants, now),
            initialConversationState,
        )

        assertEquals(listOf(ConversationStarted(id, participants, now)), events)
    }

    @Test
    fun `starting an already started conversation is rejected`() {
        val state = stateAfter(ConversationStarted(id, participants, now))

        assertFailsWith<ConversationAlreadyStarted> {
            decide(StartConversation(id, participants, now), state)
        }
    }

    @Test
    fun `a conversation needs at least two participants`() {
        assertFailsWith<TooFewParticipants> {
            decide(StartConversation(id, setOf("alice"), now), initialConversationState)
        }
    }

    @Test
    fun `posting a message to a started conversation emits MessagePosted`() {
        val state = stateAfter(ConversationStarted(id, participants, now))

        val events = decide(
            PostMessage(id, MessageId("m1"), "alice", "hello bob", now),
            state,
        )

        assertEquals(
            listOf(MessagePosted(id, MessageId("m1"), "alice", "hello bob", now)),
            events,
        )
    }

    @Test
    fun `posting to a missing conversation is rejected`() {
        assertFailsWith<ConversationNotStarted> {
            decide(
                PostMessage(id, MessageId("m1"), "alice", "hello", now),
                initialConversationState,
            )
        }
    }

    @Test
    fun `only a participant may post`() {
        val state = stateAfter(ConversationStarted(id, participants, now))

        assertFailsWith<SenderNotParticipant> {
            decide(PostMessage(id, MessageId("m1"), "eve", "hi", now), state)
        }
    }

    @Test
    fun `a message body must not be blank`() {
        val state = stateAfter(ConversationStarted(id, participants, now))

        assertFailsWith<EmptyMessageBody> {
            decide(PostMessage(id, MessageId("m1"), "alice", "   ", now), state)
        }
    }
}
