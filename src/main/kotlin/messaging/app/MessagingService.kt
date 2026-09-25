package messaging.app

import messaging.domain.ConversationCommand
import messaging.domain.decide
import messaging.domain.initialConversationState
import messaging.domain.replay
import messaging.store.EventStore

class MessagingService(private val store: EventStore) {

    fun handle(command: ConversationCommand, expectedVersion: Int): Int {
        val stream = store.read(command.conversationId)
        val state = initialConversationState.replay(stream.events)
        val newEvents = decide(command, state)
        store.append(command.conversationId, expectedVersion, newEvents)
        return expectedVersion + newEvents.size
    }
}
