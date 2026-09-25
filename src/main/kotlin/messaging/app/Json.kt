package messaging.app

import messaging.domain.ConversationEvent
import messaging.domain.ConversationStarted
import messaging.domain.MessagePosted
import messaging.projection.ConversationView
import org.http4k.core.Body
import org.http4k.format.Jackson.auto

data class StartConversationRequest(val participants: Set<String>)

data class PostMessageRequest(val sender: String, val body: String)

data class StartedResponse(val id: String, val version: Int)

data class PostedResponse(val messageId: String, val version: Int)

data class MessageDto(val messageId: String, val sender: String, val body: String, val at: String)

data class ConversationDto(
    val id: String,
    val participants: List<String>,
    val version: Int,
    val messages: List<MessageDto>,
)

data class UserConversationsDto(val user: String, val conversations: List<String>)

data class EventDto(
    val type: String,
    val conversationId: String,
    val at: String,
    val participants: List<String>? = null,
    val messageId: String? = null,
    val sender: String? = null,
    val body: String? = null,
)

data class EventsDto(val events: List<EventDto>)

val startConversationLens = Body.auto<StartConversationRequest>().toLens()
val postMessageLens = Body.auto<PostMessageRequest>().toLens()
val startedLens = Body.auto<StartedResponse>().toLens()
val postedLens = Body.auto<PostedResponse>().toLens()
val conversationLens = Body.auto<ConversationDto>().toLens()
val userConversationsLens = Body.auto<UserConversationsDto>().toLens()
val eventsLens = Body.auto<EventsDto>().toLens()

fun ConversationView.toDto() = ConversationDto(
    id = id,
    participants = participants.toList(),
    version = version,
    messages = messages.map { MessageDto(it.messageId, it.sender, it.body, it.at.toString()) },
)

fun ConversationEvent.toDto(): EventDto = when (this) {
    is ConversationStarted -> EventDto(
        type = "ConversationStarted",
        conversationId = conversationId.value,
        at = at.toString(),
        participants = participants.toList(),
    )

    is MessagePosted -> EventDto(
        type = "MessagePosted",
        conversationId = conversationId.value,
        at = at.toString(),
        messageId = messageId.value,
        sender = sender,
        body = body,
    )
}
