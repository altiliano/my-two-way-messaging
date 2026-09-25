package messaging.domain

import java.time.Instant

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageId(val value: String)

sealed interface ConversationEvent {
    val conversationId: ConversationId
}

data class ConversationStarted(
    override val conversationId: ConversationId,
    val participants: Set<String>,
    val at: Instant,
) : ConversationEvent

data class MessagePosted(
    override val conversationId: ConversationId,
    val messageId: MessageId,
    val sender: String,
    val body: String,
    val at: Instant,
) : ConversationEvent
