package net.ideadapt

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization

fun main() {
    val env = applicationEngineEnvironment {
        module {
            module()
            connector {
                // val config = env.environment.config
                port = System.getenv("SERVER_PORT")?.toInt() ?: 3000
            }
        }
    }
    embeddedServer(Netty, env).start(true)
}

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureRouting()
}
