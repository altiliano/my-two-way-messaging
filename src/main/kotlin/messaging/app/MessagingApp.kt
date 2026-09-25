package messaging.app

import messaging.domain.ConversationError
import messaging.domain.ConversationId
import messaging.domain.MessageId
import messaging.domain.PostMessage
import messaging.domain.StartConversation
import messaging.domain.ConversationAlreadyStarted
import messaging.domain.ConversationNotStarted
import messaging.projection.ConversationProjection
import messaging.projection.UserConversationsProjection
import messaging.store.ConcurrencyConflict
import messaging.store.EventStore
import messaging.store.InMemoryEventStore
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.CONFLICT
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.PRECONDITION_REQUIRED
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.LensFailure
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import java.time.Instant
import java.util.UUID

class MessagingApp(
    private val store: EventStore = InMemoryEventStore(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = Instant::now,
) {

    private val service = MessagingService(store)
    private val conversations = ConversationProjection()
    private val users = UserConversationsProjection()

    init {
        store.subscribe { conversations.on(it) }
        store.subscribe { users.on(it) }
    }

    val http: HttpHandler = errorHandling().then(
        routes(
            "/conversations" bind POST to ::startConversation,
            "/conversations/{id}/messages" bind POST to ::postMessage,
            "/conversations/{id}" bind GET to ::getConversation,
            "/users/{user}/conversations" bind GET to ::listUserConversations,
            "/events" bind GET to ::listEvents,
        ),
    )

    private fun startConversation(request: Request): Response {
        val id = ConversationId(newId())
        val participants = startConversationLens(request).participants
        val version = service.handle(StartConversation(id, participants, clock()), expectedVersion = 0)
        return Response(CREATED)
            .withETag(version)
            .with(startedLens of StartedResponse(id.value, version))
    }

    private fun postMessage(request: Request): Response {
        val expectedVersion = request.ifMatchVersion() ?: return Response(PRECONDITION_REQUIRED)
        val id = ConversationId(request.path("id")!!)
        val body = postMessageLens(request)
        val messageId = MessageId(newId())
        val command = PostMessage(id, messageId, body.sender, body.body, clock())
        val version = service.handle(command, expectedVersion)
        return Response(CREATED)
            .withETag(version)
            .with(postedLens of PostedResponse(messageId.value, version))
    }

    private fun getConversation(request: Request): Response {
        val id = ConversationId(request.path("id")!!)
        val view = conversations.get(id) ?: return Response(NOT_FOUND)
        return Response(OK)
            .withETag(view.version)
            .with(conversationLens of view.toDto())
    }

    private fun listUserConversations(request: Request): Response {
        val user = request.path("user")!!
        return Response(OK)
            .with(userConversationsLens of UserConversationsDto(user, users.conversationsOf(user)))
    }

    private fun listEvents(request: Request): Response =
        Response(OK).with(eventsLens of EventsDto(store.allEvents().map { it.toDto() }))

    private fun Response.withETag(version: Int) = header("ETag", "\"$version\"")

    private fun Request.ifMatchVersion(): Int? =
        header("If-Match")?.trim()?.trim('"')?.toIntOrNull()

    private fun errorHandling() = Filter { next ->
        { request ->
            try {
                next(request)
            } catch (e: LensFailure) {
                Response(BAD_REQUEST).body(e.message ?: "invalid request")
            } catch (e: ConcurrencyConflict) {
                Response(CONFLICT).body(e.message ?: "version conflict")
            } catch (e: ConversationNotStarted) {
                Response(NOT_FOUND).body(e.message ?: "not found")
            } catch (e: ConversationAlreadyStarted) {
                Response(CONFLICT).body(e.message ?: "already started")
            } catch (e: ConversationError) {
                Response(BAD_REQUEST).body(e.message ?: "invalid command")
            }
        }
    }
}
