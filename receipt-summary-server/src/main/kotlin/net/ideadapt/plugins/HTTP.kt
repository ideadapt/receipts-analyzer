package net.ideadapt.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureHTTP() {
    val alwaysAllow = listOf("localhost:1234", "127.0.0.1:1234", "0.0.0.0:1234")
    val hosts = System.getenv("ALLOWED_ORIGIN_HOSTS")?.split(",").orEmpty() + alwaysAllow
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader(HttpHeaders.ContentType)
        hosts.forEach { allowHost(it) }
    }
}
