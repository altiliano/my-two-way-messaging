package messaging.domain

sealed class ConversationError(message: String) : RuntimeException(message)

class ConversationAlreadyStarted(id: ConversationId) :
    ConversationError("conversation ${id.value} already started")

class ConversationNotStarted(id: ConversationId) :
    ConversationError("conversation ${id.value} does not exist")

class SenderNotParticipant(sender: String, id: ConversationId) :
    ConversationError("$sender is not a participant of ${id.value}")

class EmptyMessageBody : ConversationError("message body must not be blank")

class TooFewParticipants : ConversationError("a conversation needs at least two participants")

data class ConversationState(
    val exists: Boolean,
    val participants: Set<String>,
)

val initialConversationState = ConversationState(exists = false, participants = emptySet())

fun ConversationState.evolve(event: ConversationEvent): ConversationState = when (event) {
    is ConversationStarted -> copy(exists = true, participants = event.participants)
    is MessagePosted -> this
}

fun ConversationState.replay(events: List<ConversationEvent>): ConversationState =
    events.fold(this) { state, event -> state.evolve(event) }

fun decide(command: ConversationCommand, state: ConversationState): List<ConversationEvent> =
    when (command) {
        is StartConversation -> {
            if (state.exists) throw ConversationAlreadyStarted(command.conversationId)
            if (command.participants.size < 2) throw TooFewParticipants()
            listOf(ConversationStarted(command.conversationId, command.participants, command.at))
        }

        is PostMessage -> {
            if (!state.exists) throw ConversationNotStarted(command.conversationId)
            if (command.sender !in state.participants) {
                throw SenderNotParticipant(command.sender, command.conversationId)
            }
            if (command.body.isBlank()) throw EmptyMessageBody()
            listOf(
                MessagePosted(
                    command.conversationId,
                    command.messageId,
                    command.sender,
                    command.body,
                    command.at,
                ),
            )
        }
    }
