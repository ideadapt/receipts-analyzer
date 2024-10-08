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
import net.ideadapt.AnalysisResult.Companion.dateFormat
import net.ideadapt.AnalysisResult.LineItem
import net.ideadapt.NxClient.File
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization
import okio.Buffer
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject
import org.koin.ktor.plugin.Koin
import java.time.format.DateTimeFormatter
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
    private val nx: NxClient = NxClient(),
    private val ai: AiClient = AiClient(),
    private val syncWorkerMutex: Mutex = Mutex(),
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

        val fileAnalysis = if (file.contentType == "text/csv") {
            val result = MigrosCsv(buffer).toAnalysisResult()
            categorize(result)
            result
        } else {
            val result = ai.extractLineItems(buffer, file.name)
            categorize(result)
            // TODO convert datetime (date format depends on seller)
            result
        }

        val existingAnalysis = nx.analyzed()
        val mergedAnalysis = existingAnalysis.merge(fileAnalysis)
        // TODO how exactly are exception treated? do they stop the program or not?
        nx.storeAnalysisResult(mergedAnalysis)
    }

    private suspend fun categorize(result: AnalysisResult) {
        val categories = result.lineItems
            .windowed(50, 50, true)
            .flatMap { batch ->
                ai.categorize(batch.map { it.articleName })
            }
        // TODO categories.size still does not always match result.lineItems.size
        //  also sometimes a category column is not present
        //  maybe just retry one more time on any error!?
        result.lineItems.forEachIndexed { idx, item ->
            item.category = categories[idx]
        }
    }
}

data class MigrosCsv(private val buffer: Buffer) {
    private val migrosFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun toAnalysisResult(): AnalysisResult {
        val csv = buffer.readUtf8()
        // Datum;Zeit;Filiale;Kassennummer;Transaktionsnummer;Artikel;Menge;Aktion;Umsatz
        // 05.09.2024;12:50:16;MM Gäuggelistrasse;267;81;Alnatura Reiswaffel;0.235;0.00;1.95
        val lineItems = csv
            .trim()
            .lines()
            .drop(1) // the csv header line
            .filter { it.isNotBlank() }
            // MR = Migros restaurant
            .filter { !it.contains("CUMULUS BON") && !it.contains("Bonus-Coupon") && !it.contains(";MR ") }
            .map { line ->
                val parts = line.split(";")
                val articleName = parts[5]
                val quantity = parts[6].toDouble()
                val total = parts[8].toDouble()
                val itemPrice = String.format("%.2f", (total / (1 / quantity)))
                val dateTime = dateFormat.format(migrosFormatter.parse("${parts[0]} ${parts[1]}"))
                val seller = "Migros"
                val category = ""
                val values = listOf(articleName, quantity, itemPrice, total, dateTime, seller, category)
                LineItem(csv = values.joinToString(","))
            }
            .toSet()
        return AnalysisResult(lineItems)
    }
}

data class AnalysisResult(
    val lineItems: Set<LineItem>
) {
    private val analysisResultHeader = "Artikelbezeichnung,Menge,Preis,Total,Datetime,Seller,Category"

    /**
     * [csv] has to be a comma separated list of values, see [analysisResultHeader].
     */
    data class LineItem(val csv: String) {
        private val parts: List<String> by lazy { csv.split(",").map { it.trim() } }
        val articleName = parts[0]
        val quantity = parts[1]
        val itemPrice = parts[2]
        val totalPrice = parts[3]
        val dateTime = parts[4]
        val seller = parts[5]
        var category = parts[6]

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

        override fun toString(): String = "$articleName,$quantity,$itemPrice,$totalPrice,$dateTime,$seller,$category"
    }

    override fun toString() = "$analysisResultHeader\n${lineItems.joinToString("\n")}"

    companion object {
        val dateFormat: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME
    }
}

fun AnalysisResult.Companion.fromCsvWithHeader(csv: String) = AnalysisResult(
    lineItems = csv.lines().drop(1).map { LineItem(it) }.toSet()
)

fun AnalysisResult.merge(new: AnalysisResult): AnalysisResult {
    val mergedLineItems = this.lineItems.plus(new.lineItems)
    return AnalysisResult(mergedLineItems)
}
