package messaging.projection

import messaging.domain.ConversationEvent
import messaging.domain.ConversationId
import messaging.domain.ConversationStarted
import messaging.domain.MessagePosted
import java.time.Instant

data class MessageView(
    val messageId: String,
    val sender: String,
    val body: String,
    val at: Instant,
)

data class ConversationView(
    val id: String,
    val participants: Set<String>,
    val version: Int,
    val messages: List<MessageView>,
)

class ConversationProjection {

    private val views = mutableMapOf<ConversationId, ConversationView>()

    fun on(event: ConversationEvent) {
        val current = views[event.conversationId]
        views[event.conversationId] = when (event) {
            is ConversationStarted -> ConversationView(
                id = event.conversationId.value,
                participants = event.participants,
                version = 1,
                messages = emptyList(),
            )

            is MessagePosted -> current!!.copy(
                version = current.version + 1,
                messages = current.messages + MessageView(
                    messageId = event.messageId.value,
                    sender = event.sender,
                    body = event.body,
                    at = event.at,
                ),
            )
        }
    }

    fun get(id: ConversationId): ConversationView? = views[id]
}
