package messaging

import messaging.app.MessagingApp
import org.http4k.server.SunHttp
import org.http4k.server.asServer

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = MessagingApp().http.asServer(SunHttp(port)).start()
    println("messaging api listening on http://localhost:${server.port()}")
}
