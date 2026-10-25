package net.ideadapt

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.sha1
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.encodeToByteString
import net.ideadapt.AnalysisResult.Companion.outputDateFormat
import net.ideadapt.AnalysisResult.LineItem
import net.ideadapt.NxClient.File
import net.ideadapt.plugins.configureDependencyInjection
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization
import okio.Buffer
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import kotlin.time.Duration.Companion.seconds

val logger: Logger = LoggerFactory.getLogger("net.ideadapt.receiptsSummary.main")

fun main() {
    embeddedServer(
        factory = Netty,
        port = Config.get("SERVER_PORT")?.toInt() ?: 3000,
        module = Application::receiptsModule
    )
        .start(wait = false)

    val worker by inject<Worker>(Worker::class.java)

    runBlocking {
        launch(Dispatchers.IO) {
            logger.info("ready to accept requests")

            tryInitialSyncTwice(logger, worker)

            while (true) {
                delay(5.seconds)
            }
        }
    }
}

private suspend fun tryInitialSyncTwice(logger: Logger, worker: Worker) {
    try {
        trySync(logger, worker)
    } catch (e: Exception) {
        logger.error("error in initial sync. Trying 1 more time.", e)

        try {
            trySync(logger, worker)
        } catch (ex: Exception) {
            logger.error("error in initial sync.", ex)
        }
    }
}

private suspend fun trySync(logger: Logger, worker: Worker) {
    // withContext allows try catch on call site
    withContext(Dispatchers.IO) {
        logger.info("starting initial sync")
        worker.sync()
        logger.info("done initial sync")
    }
}

fun Application.receiptsModule() {
    configureHTTP()
    configureSerialization()
    configureRouting()
    configureDependencyInjection()
}

object Config {
    private val props: Properties = Properties()

    init {
        props.load(javaClass.classLoader.getResourceAsStream(".env"))
    }

    fun get(key: String): String? = props[key]?.toString()
}

class Worker(
    private val nx: NxClient = NxClient(),
    private val ai: AiClient = AiClient(),
    private val syncWorkerMutex: Mutex = Mutex(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun sync() {
        val doneFileNameId = "_done."
        syncWorkerMutex.withLock {
            val candidateFiles = nx.files().filter { !it.name.contains(doneFileNameId) }
            candidateFiles.forEach { nextFile ->
                analyse(nextFile)
                nx.rename(nextFile,   "done/" + nextFile.name)
            }
        }
    }

    suspend fun sync(file: File) {
        syncWorkerMutex.withLock {
            analyse(file)
        }
    }

    private suspend fun analyse(file: File) {
        val buffer = nx.readFile(file.name)

        if (file.contentType == "text/csv") {
            val result = MigrosCsv(buffer).toAnalysisResult()
            categorize(result)
        } else {
            val result = ai.extractLineItemsFromPDF(buffer, file.name)
            categorize(result)
        }
    }

    private suspend fun categorize(result: AnalysisResult) {
        val existingAnalysis = nx.analyzed()
        val maxAttempts = 4
        result.lineItems.minus(existingAnalysis.lineItems)
            .windowed(100, 100, true)
            .mapIndexed { index, batch ->
                var attempt = 0
                var categories: List<String>
                var tooManyUnknowns: Boolean
                do {
                    attempt++
                    if (attempt > 1) {
                        logger.debug("trying to categorize again, attempt: $attempt of $maxAttempts, batch: $index")
                    }

                    val itemNames = batch.map { lineItem -> "${lineItem.id},${lineItem.articleName}" }
                    logger.info("categorizing ${itemNames.size} items, batch: $index")
                    categories = ai.categorize(itemNames)
                    val unknowns = categories.filter { it.substringAfterLast(",").trim() == "-" }
                    logger.debug("categorized ${itemNames.size} items into ${categories.size} categorized items, unknowns: ${unknowns.size}, batch: $index")

                    tooManyUnknowns = (unknowns.size / (batch.size / 100.0)) > 50
                    if (tooManyUnknowns) {
                        logger.info("too many unknown categories: ${unknowns.size}, batch: $index. categories: $unknowns")
                    }
                } while ((categories.size != batch.size || tooManyUnknowns) && attempt < maxAttempts)

                val idToLineItem = batch.associateBy { it.id }
                val updatedIds = categories.mapNotNull { articleCategory ->
                    val (id, _, category) = articleCategory.split(",").map { it.trim() }
                    if (!idToLineItem.containsKey(id)) {
                        logger.error("unable to apply category '$category' to line item with id '$id': id does not exist. raw categorization result: '$articleCategory'")
                        null
                    } else {
                        idToLineItem[id]!!.category = category
                        id
                    }
                }.toSet()

                idToLineItem.keys.minus(updatedIds).forEach { notUpdatedId ->
                    idToLineItem[notUpdatedId]!!.category = "-"
                }

                val existingAnalysis = nx.analyzed()
                val batchResult = AnalysisResult(batch.toSet())
                val mergedAnalysis = existingAnalysis.merge(batchResult)
                logger.info(
                    "merged ${existingAnalysis.lineItems.size} existing items with ${batchResult.lineItems.size} new items." +
                            " Size after merge: ${mergedAnalysis.lineItems.size}."
                )
                nx.saveAnalysisResult(mergedAnalysis)
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
            .filter {
                // MR = Migros restaurant
                !it.contains("CUMULUS BON") && !it.contains("CUM ")
                        && !it.contains("Bonus-Coupon") && !it.contains(";MR ")
            }
            .mapNotNull { line ->
                try {
                    val parts = line.split(";")
                    val articleName = parts[5]
                    val quantity = parts[6].toDouble()
                    val total = parts[8].toDouble()
                    val itemPrice = String.format("%.2f", (total / (1 / quantity)))
                    val dateTime = outputDateFormat.format(migrosFormatter.parse("${parts[0]} ${parts[1]}"))
                    val seller = "Migros"
                    val category = ""
                    val values = listOf(articleName, quantity, itemPrice, total, dateTime, seller, category)
                    LineItem(csv = values.joinToString(","))
                } catch (e: Exception) {
                    logger.error("skipping line '$line', parse failed.", e)
                    null
                }
            }
            .toSet()
        return AnalysisResult(lineItems)
    }
}

data class AnalysisResult(
    val lineItems: Set<LineItem>
) {
    private val analysisResultHeader = "Artikelbezeichnung,Menge,Preis,Total,Datetime,Seller,Category"

    fun merge(new: AnalysisResult): AnalysisResult {
        val mergedLineItems = this.lineItems.plus(new.lineItems)
        return AnalysisResult(mergedLineItems)
    }

    override fun toString() = "$analysisResultHeader\n${lineItems.joinToString("\n")}"

    /**
     * [csv] has to be a comma separated list of values, see [analysisResultHeader].
     */
    data class LineItem(val csv: String) {
        private val parts: List<String> by lazy { csv.replace("\"", "").split(",").map { it.trim() } }
        val articleName = parts[0]
        val quantity = parts[1]
        val itemPrice = parts[2]
        val totalPrice = parts[3]
        val dateTime = normalizeDateTime(parts[4])
        val seller = parts[5]
        var category = parts[6]

        val id = "$articleName:$totalPrice:$dateTime:$seller"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LineItem

            // this ignores the fact that the same article+totalPrice could be present twice in a single receipt
            // in such rare cases, only one instance is considered, the others are ignored.
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

    companion object {
        val outputDateFormat: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

        private val supportedInputDatePatterns = listOf(
            "yyyy-MM-dd'T'HH:mm",
            "yyyy.MM.dd HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "dd.MM.yy HH:mm",
            "dd-MM-yy HH:mm",
            "dd.MM.yyyy HH:mm",
            "dd-MM-yyyy HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss",
        )

        fun normalizeDateTime(dateTimeString: String): String {
            for (pattern in supportedInputDatePatterns) {
                try {
                    val formatter = DateTimeFormatter.ofPattern(pattern)
                    val parsedDateTime = LocalDateTime.parse(dateTimeString, formatter)
                    return outputDateFormat.format(parsedDateTime)
                } catch (e: DateTimeParseException) {
                    // Continue trying the next pattern
                }
            }

            return dateTimeString
        }

        fun fromCsvWithHeader(csv: String) = AnalysisResult(
            lineItems = csv.lines().drop(1).map { LineItem(it) }.toSet()
        )
    }
}
