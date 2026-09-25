package messaging.app

import com.fasterxml.jackson.databind.JsonNode
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.format.Jackson
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagingApiTest {

    private var counter = 0
    private val app = MessagingApp(
        newId = { "id-${++counter}" },
        clock = { Instant.parse("2026-01-01T00:00:00Z") },
    ).http

    private fun json(body: String): JsonNode = Jackson.parse(body)

    private fun startConversation(vararg participants: String): JsonNode {
        val payload = participants.joinToString(",") { "\"$it\"" }
        val response = app(
            Request(POST, "/conversations").body("""{"participants":[$payload]}"""),
        )
        assertEquals(Status.CREATED, response.status)
        return json(response.bodyString())
    }

    @Test
    fun `starting a conversation returns its id, version and ETag`() {
        val response = app(
            Request(POST, "/conversations").body("""{"participants":["alice","bob"]}"""),
        )

        assertEquals(Status.CREATED, response.status)
        assertEquals("\"1\"", response.header("ETag"))
        val body = json(response.bodyString())
        assertEquals("id-1", body["id"].asText())
        assertEquals(1, body["version"].asInt())
    }

    @Test
    fun `too few participants is a bad request`() {
        val response = app(
            Request(POST, "/conversations").body("""{"participants":["alice"]}"""),
        )

        assertEquals(Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `posting a message with a matching If-Match succeeds and advances the version`() {
        val id = startConversation("alice", "bob")["id"].asText()

        val response = app(
            Request(POST, "/conversations/$id/messages")
                .header("If-Match", "\"1\"")
                .body("""{"sender":"alice","body":"hi bob"}"""),
        )

        assertEquals(Status.CREATED, response.status)
        assertEquals("\"2\"", response.header("ETag"))
        assertEquals(2, json(response.bodyString())["version"].asInt())
    }

    @Test
    fun `posting a message without If-Match is precondition required`() {
        val id = startConversation("alice", "bob")["id"].asText()

        val response = app(
            Request(POST, "/conversations/$id/messages")
                .body("""{"sender":"alice","body":"hi"}"""),
        )

        assertEquals(Status.PRECONDITION_REQUIRED, response.status)
    }

    @Test
    fun `posting a message with a stale If-Match conflicts`() {
        val id = startConversation("alice", "bob")["id"].asText()
        app(
            Request(POST, "/conversations/$id/messages")
                .header("If-Match", "\"1\"")
                .body("""{"sender":"alice","body":"first"}"""),
        )

        val stale = app(
            Request(POST, "/conversations/$id/messages")
                .header("If-Match", "\"1\"")
                .body("""{"sender":"bob","body":"second"}"""),
        )

        assertEquals(Status.CONFLICT, stale.status)
    }

    @Test
    fun `a non-participant cannot post`() {
        val id = startConversation("alice", "bob")["id"].asText()

        val response = app(
            Request(POST, "/conversations/$id/messages")
                .header("If-Match", "\"1\"")
                .body("""{"sender":"eve","body":"hi"}"""),
        )

        assertEquals(Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `getting a conversation returns its messages and version ETag`() {
        val id = startConversation("alice", "bob")["id"].asText()
        app(
            Request(POST, "/conversations/$id/messages")
                .header("If-Match", "\"1\"")
                .body("""{"sender":"alice","body":"hi bob"}"""),
        )

        val response = app(Request(GET, "/conversations/$id"))

        assertEquals(Status.OK, response.status)
        assertEquals("\"2\"", response.header("ETag"))
        val body = json(response.bodyString())
        assertEquals(id, body["id"].asText())
        assertEquals("hi bob", body["messages"][0]["body"].asText())
        assertEquals("alice", body["messages"][0]["sender"].asText())
    }

    @Test
    fun `getting an unknown conversation is not found`() {
        assertEquals(Status.NOT_FOUND, app(Request(GET, "/conversations/nope")).status)
    }

    @Test
    fun `listing a user's conversations reflects participation`() {
        val first = startConversation("alice", "bob")["id"].asText()
        val second = startConversation("alice", "carol")["id"].asText()
        startConversation("bob", "carol")

        val response = app(Request(GET, "/users/alice/conversations"))

        assertEquals(Status.OK, response.status)
        val ids = json(response.bodyString())["conversations"].map { it.asText() }
        assertEquals(listOf(first, second), ids)
    }

    @Test
    fun `the event log exposes the raw events`() {
        startConversation("alice", "bob")

        val response = app(Request(GET, "/events"))

        assertEquals(Status.OK, response.status)
        val events = json(response.bodyString())["events"]
        assertTrue(events.any { it["type"].asText() == "ConversationStarted" })
    }
}
