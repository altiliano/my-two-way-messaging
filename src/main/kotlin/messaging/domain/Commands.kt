package messaging.domain

import java.time.Instant

sealed interface ConversationCommand {
    val conversationId: ConversationId
}

data class StartConversation(
    override val conversationId: ConversationId,
    val participants: Set<String>,
    val at: Instant,
) : ConversationCommand

data class PostMessage(
    override val conversationId: ConversationId,
    val messageId: MessageId,
    val sender: String,
    val body: String,
    val at: Instant,
) : ConversationCommand
