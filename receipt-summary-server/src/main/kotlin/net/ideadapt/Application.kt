package net.ideadapt

import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.assistant.AssistantRequest
import com.aallam.openai.api.assistant.FileSearchResources
import com.aallam.openai.api.assistant.ToolResources
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.core.Status
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.file.FileUpload
import com.aallam.openai.api.file.Purpose
import com.aallam.openai.api.message.MessageContent
import com.aallam.openai.api.run.ThreadRunRequest
import com.aallam.openai.api.thread.ThreadMessage
import com.aallam.openai.api.thread.threadRequest
import com.aallam.openai.api.vectorstore.VectorStoreRequest
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import net.ideadapt.NxClient.File
import net.ideadapt.plugins.FileAnalysisResult
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import okio.Buffer
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
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

/**
 * NextCloud WebDAV client featuring methods to read and write files based on shares.
 *
 * Requires server IP to be ignored for NextCloud bruteforce detection:
 *  https://docs.nextcloud.com/server/29/admin_manual/configuration_server/bruteforce_configuration.html
 *
 * @See https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
 **/
data class NxClient(
    private val shareId: String = requireNotNull(System.getenv("SHARE_ID")) { "SHARE_ID missing" },
    private val sharePassword: String = requireNotNull(System.getenv("SHARE_PASSWORD")) { "SHARE_PASSWORD missing" },
    private val stateId: String = requireNotNull(System.getenv("STATE_ID")) { "STATE_ID missing" },
    private val statePassword: String = requireNotNull(System.getenv("STATE_PASSWORD")) { "STATE_PASSWORD missing" },
    private val analyzedId: String = requireNotNull(System.getenv("ANALYZED_ID")) { "ANALYZED_ID missing" },
    private val analyzedPassword: String = requireNotNull(System.getenv("ANALYZED_PASSWORD")) { "ANALYZED_PASSWORD missing" },
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout)
    },
    private val nxFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z"),
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    suspend fun files(): SortedSet<File> {
        logger.info("getting files")
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId") {
            method = HttpMethod("PROPFIND")
            timeout {
                requestTimeoutMillis = 60.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", sharePassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
        }

        if (!resp.status.isSuccess()) {
            logger.error("Error getting files of folder $shareId. status: {}, body: {}", resp.status, resp.bodyAsText())
            return sortedSetOf<File>()
        }

        val xml = resp.bodyAsText()
        val parsedXml = parseXml(xml)
        val files = mutableSetOf<File>()

        parsedXml.responses
            .forEach { r ->
                val folderFiles = r.propstats
                    .filter { p -> p.prop.quotaUsedBytes == null /*only folder has quotaUsedBytes set*/ }
                folderFiles.forEach { f ->
                    files.add(
                        File(
                            name = r.href.substringAfterLast("/"),
                            etag = f.prop.getEtag.replace("\"", ""),
                            lastModified = ZonedDateTime.parse(f.prop.getLastModified, nxFormatter)
                        )
                    )
                }
            }

        logger.info("getting files: found ${files.size} files")

        return files.toSortedSet(compareBy { it.lastModified })
    }

    suspend fun file(fileName: String): Buffer {
        logger.info("getting file $fileName")
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId/${fileName}") {
            method = HttpMethod("GET")
            timeout {
                requestTimeoutMillis = 60.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", sharePassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
        }

        if (!resp.status.isSuccess()) {
            logger.error("Error getting files of folder $shareId. status: {}, body: {}", resp.status, resp.bodyAsText())
            throw IllegalStateException(
                String.format(
                    "Error getting file $$shareId/$fileName. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText()
                )
            )
        }
        logger.info("found file $fileName, size: ${resp.headers["Content-Length"]} bytes")

        return Buffer().readFrom(resp.bodyAsChannel().toInputStream())
    }

    suspend fun state(): State {
        logger.info("getting state")
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$stateId") {
            method = HttpMethod("GET")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", statePassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error getting state $stateId. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText()
                )
            )
        }
        logger.info("found state, size: ${resp.headers["Content-Length"]} bytes")

        val csv = resp.bodyAsText()
        return State(csv)
    }

    suspend fun storeState(state: State) {
        logger.info("storing state ${state.csv.take(50)}...")
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$stateId") {
            method = HttpMethod("PUT")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", statePassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
            setBody(state.csv)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error storing state $stateId. status: %s, body: %s ...",
                    resp.status,
                    resp.bodyAsText().take(200)
                )
            )
        }
        logger.info("stored state")
    }

    private suspend fun analyzed(): String {
        logger.info("getting analyzed")
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$analyzedId") {
            method = HttpMethod("GET")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", analyzedPassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error getting analyzed $analyzedId. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText()
                )
            )
        }
        logger.info("found analyzed, size: ${resp.headers["Content-Length"]} bytes")

        return resp.bodyAsText()
    }

    suspend fun storeAnalysisResult(analysisResult: FileAnalysisResult) {
        logger.info("storing analysis result ...${analysisResult.csv.takeLast(50)}")
        val existingAnalysis = analyzed()
        val newAnalysis = existingAnalysis + "\n" + analysisResult.csv
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$analyzedId") {
            method = HttpMethod("PUT")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", analyzedPassword)
            retry {
                maxRetries = 1
                constantDelay(1000)
            }
            setBody(newAnalysis)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error storing analysis result $analysisResult. status: %s, body: %s ...",
                    resp.status,
                    resp.bodyAsText().take(200)
                )
            )
        }
        logger.info("stored analysis result")
    }

    data class State(val csv: String) {
        private val etags: Set<String> = csv.split(",").toSet()

        fun unprocessed(candidates: Set<File>): SortedSet<File> {
            return candidates
                .filter { c -> etags.none { etag -> etag == c.etag } }
                .toSortedSet(compareBy { it.lastModified })
        }

        fun done(file: File): State {
            return State(etags.plus(file.etag).joinToString(","))
        }
    }

    data class File(val name: String, val etag: String, val lastModified: ZonedDateTime)
}

@Language("XML")
val xml = """
    <?xml version="1.0"?>
    <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
        <d:response>
            <d:href>/nextcloud/public.php/dav/files/fyQkE8Y9wafLEi4/</d:href>
            <d:propstat>
                <d:prop>
                    <d:getlastmodified>Sun, 06 Oct 2024 15:33:30 GMT</d:getlastmodified>
                    <d:resourcetype>
                        <d:collection/>
                    </d:resourcetype>
                    <d:quota-used-bytes>260048</d:quota-used-bytes>
                    <d:quota-available-bytes>-3</d:quota-available-bytes>
                    <d:getetag>&quot;6702adca2db38&quot;</d:getetag>
                </d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
        </d:response>
        <d:response>
            <d:href>/nextcloud/public.php/dav/files/fyQkE8Y9wafLEi4/Receipt_20240717_175812_0090466_252_113.pdf</d:href>
            <d:propstat>
                <d:prop>
                    <d:getlastmodified>Sat, 28 Sep 2024 11:58:23 GMT</d:getlastmodified>
                    <d:getcontentlength>130857</d:getcontentlength>
                    <d:resourcetype/>
                    <d:getetag>&quot;bdbc72ff8e77ad2080987018f6954ba7&quot;</d:getetag>
                    <d:getcontenttype>application/pdf</d:getcontenttype>
                </d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
        </d:response>
        <d:response>
            <d:href>/nextcloud/public.php/dav/files/fyQkE8Y9wafLEi4/Receipt_20240718_151446_0090466_003_347.pdf</d:href>
            <d:propstat>
                <d:prop>
                    <d:getlastmodified>Sat, 28 Sep 2024 11:58:34 GMT</d:getlastmodified>
                    <d:getcontentlength>129191</d:getcontentlength>
                    <d:resourcetype/>
                    <d:getetag>&quot;df7f3873851715ca368b401e46b75d3e&quot;</d:getetag>
                    <d:getcontenttype>application/pdf</d:getcontenttype>
                </d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
        </d:response>
    </d:multistatus>
""".trimIndent()

@Serializable
@XmlSerialName("multistatus", "DAV:", "d")
data class Multistatus(
    @XmlElement(true) // Unwrapped list of responses
    @XmlSerialName("response", "DAV:", "d")
    val responses: List<Response>
)

@Serializable
@XmlSerialName("response", "DAV:", "d")
data class Response(
    @XmlSerialName("href", "DAV:", "d")
    @XmlElement(true)
    val href: String,

    @XmlElement(true) // Unwrapped list of propstat elements
    @XmlSerialName("propstat", "DAV:", "d")
    val propstats: List<Propstat>
)

@Serializable
@XmlSerialName("propstat", "DAV:", "d")
data class Propstat(
    @XmlSerialName("prop", "DAV:", "d")
    val prop: Prop,

    @XmlSerialName("status", "DAV:", "d")
    @XmlElement(true)
    val status: String
)

@Serializable
@XmlSerialName("prop", "DAV:", "d")
data class Prop(
    @XmlSerialName("getlastmodified", "DAV:", "d")
    @XmlElement(true)
    val getLastModified: String,

    @XmlSerialName("getcontentlength", "DAV:", "d")
    @XmlElement(true)
    val getContentLength: Long? = null,

    @XmlSerialName("getetag", "DAV:", "d")
    @XmlElement(true)
    val getEtag: String,

    @XmlSerialName("getcontenttype", "DAV:", "d")
    @XmlElement(true)
    val getContentType: String? = null,

    @XmlSerialName("quota-used-bytes", "DAV:", "d")
    @XmlElement(true)
    val quotaUsedBytes: Long? = null,

    @XmlSerialName("quota-available-bytes", "DAV:", "d")
    @XmlElement(true)
    val quotaAvailableBytes: Long? = null,

    @XmlSerialName("resourcetype", "DAV:", "d")
    @XmlElement(true)
    val resourceType: ResourceType? = null
)

@Serializable
@XmlSerialName("resourcetype", "DAV:", "d")
data class ResourceType(
    @XmlSerialName("collection", "DAV:", "d")
    @XmlElement(true)
    val isCollection: Boolean = false
)

fun parseXml(xml: String): Multistatus {
    val xmlParser = XML {
        autoPolymorphic = false
    }

    return xmlParser.decodeFromString(xml)
}

/**
 * OpenAI client featuring methods to analyze file contents with following specializations:
 *  - extract line items and other metadata in a receipt of Migros
 */
class AiClient(
    private val token: String = requireNotNull(System.getenv("OPEN_AI_TOKEN")) { "OPEN_AI_TOKEN missing" },
    private val ai: OpenAI = OpenAI(config = OpenAIConfig(token = token))
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    @OptIn(BetaOpenAI::class)
    suspend fun analyze(content: Buffer, fileName: String): FileAnalysisResult {
        logger.info("analyzing $fileName")
        val aiFile = ai.file(
            FileUpload(
                purpose = Purpose("assistants"),
                file = FileSource(name = fileName, source = content)
            )
        )
        val vectorStore = ai.createVectorStore(VectorStoreRequest(name = "receipts", fileIds = listOf(aiFile.id)))

        val assistantName = "asst_pdf_receipts_reader_v7"
        val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
            AssistantRequest(
                name = assistantName,
                instructions = """
        |You can read tabular data from a shopping receipt and output this data in proper CSV format.
        |You never include anything but the raw CSV rows. You omit the surrounding markdown code blocks.
        |Make sure you never remove the header row containing the column titles.
        |You always add an extra column at the end called 'Category', which categorizes the shopping item based on its name.
        |The receipts are in german, so you have to use german category names. Try to use one of the following category names: Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck.
        |You may add another category if none of the examples match.Add another extra column at the end called 'Datetime' that contains the date and time of the receipt. The receipt date and time value is the same for every shopping item.
        |Add another extra column at the end called 'Seller' that contains the name of the receipt issuer (e.g. store name). The seller value is the same for every shopping item.
        |If the seller name contains one of: 'Migros', 'Coop', 'Aldi', 'Lidl', use that short form.
        |""".trimMargin()
            )
        )

        val aiThreadRun = ai.createThreadRun(
            request = ThreadRunRequest(
                assistantId = assistant.id,
                thread = threadRequest {
                    toolResources =
                        ToolResources(fileSearch = FileSearchResources(vectorStoreIds = listOf(vectorStore.id)))
                    messages = listOf(
                        ThreadMessage(
                            content = "Extract tabular data from attached receipt. The columns in the receipt are Artikelbezeichnung, Menge, Preis, Total.",
                            role = Role.User
                        )
                    )
                }
            ))

        do {
            delay(1500)
            val retrievedRun = ai.getRun(threadId = aiThreadRun.threadId, runId = aiThreadRun.id)
        } while (retrievedRun.status != Status.Completed)

        val csv = ai.messages(aiThreadRun.threadId).map {
            it.content.first() as? MessageContent.Text ?: error("Expected MessageContent.Text")
        }
            .map { it.text.value }
            .dropLast(1)

        logger.info("analyzed $fileName, line items: ${csv.size}")

        return FileAnalysisResult(csv = csv.joinToString("\n"))
    }
}
