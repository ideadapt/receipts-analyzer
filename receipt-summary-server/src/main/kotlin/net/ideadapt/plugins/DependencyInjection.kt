package net.ideadapt.plugins

import io.ktor.server.application.*
import net.ideadapt.Worker
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

fun Application.configureDependencyInjection() {
    install(Koin) {
        modules(listOf(
            module {
                single { Worker() }
            }
        ))
    }
}
