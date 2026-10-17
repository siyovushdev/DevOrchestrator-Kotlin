package dev.orchestrator

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** A deliberately read-only bootstrap endpoint. No agents or GitHub credentials are used. */
fun Application.module() {
    routing {
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
    }
}

fun main() {
    val port = System.getenv("DEVORCH_PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: 8080

    embeddedServer(Netty, host = "127.0.0.1", port = port) {
        module()
    }.start(wait = true)
}
