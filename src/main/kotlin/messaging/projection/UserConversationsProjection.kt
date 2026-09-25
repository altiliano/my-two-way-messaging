package messaging.projection

import messaging.domain.ConversationEvent
import messaging.domain.ConversationStarted
import messaging.domain.MessagePosted

class UserConversationsProjection {

    private val byUser = mutableMapOf<String, MutableList<String>>()

    fun on(event: ConversationEvent) {
        when (event) {
            is ConversationStarted -> event.participants.forEach { user ->
                byUser.getOrPut(user) { mutableListOf() }.add(event.conversationId.value)
            }

            is MessagePosted -> Unit
        }
    }

    fun conversationsOf(user: String): List<String> = byUser[user]?.toList() ?: emptyList()
}
