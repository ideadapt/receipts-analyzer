package net.ideadapt

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ideadapt.NxClient.File
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(
        factory = Netty,
        environment = applicationEngineEnvironment {
            module {
                receiptsModule()
                connector {
                    port = System.getenv("SERVER_PORT")?.toInt() ?: 3000
                }
            }
        })
        .start(false)

    runBlocking {
        launch(Dispatchers.IO) {
            sync()

            while (true) {
                delay(5.seconds)
            }
        }
    }
}

fun Application.receiptsModule() {
    configureHTTP()
    configureSerialization()
    configureRouting()
}

val syncWorkerMutex = Mutex()
val nx = NxClient()
val ai = AiClient()

// TODO nextcloud create a new etag if the same file is deleted and uploaded again
//  this results in another analysis for a (potentially) already analyzed file,
//  which will finally append again the to analyzed csv.
//  we could use the filename instead of etag, but then - renamed files cause the same problem.
//  in the final app vision, neither renames nor re-uploads can happen (at least not via app APIs).
suspend fun sync() {
    syncWorkerMutex.withLock {
        var state = nx.state()

        val candidateFiles = nx.files()
        state.unprocessed(candidateFiles).forEach { nextFile ->
            val buffer = nx.file(nextFile.name)
            // TODO branch for migros CSV files (ignore MR=migros restaurant, ignore CUMULUS BON, ...)
            val analysisResult = ai.analyze(buffer, nextFile.name)
            // TODO how exactly are exception treated? do they stop the program or not?
            nx.storeAnalysisResult(analysisResult)

            state = state.done(nextFile)
            nx.storeState(state)
        }
    }
}

suspend fun sync(file: File) {
    syncWorkerMutex.withLock {
        var state = nx.state()

        val buffer = nx.file(file.name)
        val analysisResult = ai.analyze(buffer, file.name)
        nx.storeAnalysisResult(analysisResult)

        state = state.done(file)
        nx.storeState(state)
    }
}

