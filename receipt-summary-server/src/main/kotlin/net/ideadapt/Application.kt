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
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject
import org.koin.ktor.plugin.Koin
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

    val worker by inject<Worker>(Worker::class.java)

    runBlocking {
        launch(Dispatchers.IO) {
            worker.sync()

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
    install(Koin) {
        modules(listOf(
            module {
                single { Worker() }
            }
        ))
    }
}

class Worker(
    val nx: NxClient = NxClient(),
    val ai: AiClient = AiClient(),
    val syncWorkerMutex: Mutex = Mutex(),
) {
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
                analyse(nextFile)

                state = state.done(nextFile)
                nx.storeState(state)
            }
        }
    }

    suspend fun sync(file: File) {
        syncWorkerMutex.withLock {
            var state = nx.state()

            analyse(file)

            state = state.done(file)
            nx.storeState(state)
        }
    }

    private suspend fun analyse(file: File) {
        val buffer = nx.file(file.name)
        // TODO branch for migros CSV files (ignore MR=migros restaurant, ignore CUMULUS BON, ...)
        val fileAnalysis = ai.analyze(buffer, file.name)
        val existingAnalysis = nx.analyzed()
        val mergedAnalysis = existingAnalysis.merge(fileAnalysis)
        // TODO how exactly are exception treated? do they stop the program or not?
        nx.storeAnalysisResult(mergedAnalysis)
    }
}

data class AnalysisResult(
    val csv: String,
    val header: String = csv.lines()[0],
    val lineItems: Set<LineItem> = csv.lines().drop(1).map { LineItem(it) }.toSet()
) {

    data class LineItem(val csv: String) {
        private val parts: List<String> by lazy { csv.split(",") }
        val articleName = parts[0]
        val quantity = parts[1]
        val itemPrice = parts[2]
        val totalPrice = parts[3]
        val category = parts[4]
        val dateTime = parts[5]
        val seller = parts[6]

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LineItem

            if (articleName != other.articleName) return false
            if (totalPrice != other.totalPrice) return false
            if (dateTime != other.dateTime) return false
            if (seller != other.seller) return false

            return true
        }

        override fun hashCode(): Int {
            var result = articleName.hashCode()
            result = 31 * result + totalPrice.hashCode()
            result = 31 * result + dateTime.hashCode()
            result = 31 * result + seller.hashCode()
            return result
        }

        override fun toString(): String = csv
    }
}

fun AnalysisResult.merge(new: AnalysisResult): AnalysisResult {
    val mergedLineItems = this.lineItems.plus(new.lineItems)
    return AnalysisResult(csv = "${header}\n${mergedLineItems.joinToString("\n")}")
}
