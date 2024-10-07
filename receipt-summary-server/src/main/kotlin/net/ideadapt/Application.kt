package net.ideadapt

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import net.ideadapt.NxClient.File
import net.ideadapt.plugins.configureHTTP
import net.ideadapt.plugins.configureRouting
import net.ideadapt.plugins.configureSerialization
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.intellij.lang.annotations.Language
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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

val workerMutex = Mutex()
val nx = NxClient()

suspend fun sync() {
    workerMutex.withLock {
        var state = nx.state()
        state = state.start()
        nx.storeState(state)

        val candidateFiles = nx.files()
        state.unprocessed(candidateFiles).forEach { nextFile ->
            // try buffer = nx.file(nextFile)
            // ai.analyze(buffer)
            delay(5.seconds)
            state = state.done(nextFile)
            nx.storeState(state)
        }

        state = state.done()
        nx.storeState(state)
    }
}

suspend fun sync(file: File) {
    workerMutex.withLock {
        var state = nx.state()
        state = state.start()
        nx.storeState(state)

        // try buffer = nx.file(nextFile)
        // ai.analyze(buffer)
        delay(5.seconds)
        state = state.done(file)
        nx.storeState(state)

        state = state.done()
        nx.storeState(state)
    }
}

data class NxClient(
    private val shareId: String = requireNotNull(System.getenv("SHARE_ID")) { "SHARE_ID missing" },
    private val sharePassword: String = requireNotNull(System.getenv("SHARE_PASSWORD")) { "SHARE_PASSWORD missing" },
    private val stateId: String = requireNotNull(System.getenv("STATE_ID")) { "STATE_ID missing" },
    private val statePassword: String = requireNotNull(System.getenv("STATE_PASSWORD")) { "STATE_PASSWORD missing" },
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout)
    },
    private val nxFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
) {

    // TODO could also be just the folders etag, more simple than date, but less information
    suspend fun modifiedSince(since: ZonedDateTime?): Boolean {
        if (since == null) return true

        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId") {
            method = HttpMethod("PROPFIND")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", sharePassword)
            headers {
                append("Depth", "0")
            }
        }

        val xml = resp.bodyAsText()
        val lastModifiedString = xml.substringAfter("<d:getlastmodified>").substringBefore("</d:getlastmodified>")
        val lastModified = ZonedDateTime.parse(lastModifiedString, nxFormatter)

        return lastModified.isAfter(since)
    }

    suspend fun files(): SortedSet<File> {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId") {
            method = HttpMethod("PROPFIND")
            timeout {
                requestTimeoutMillis = 60.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", sharePassword)
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
                            name = r.href,
                            etag = f.prop.getEtag.replace("\"", ""),
                            lastModified = ZonedDateTime.parse(f.prop.getLastModified, nxFormatter)
                        )
                    )
                }
            }

        return files.toSortedSet(compareBy { it.lastModified })
    }

    suspend fun state(): State {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$stateId") {
            method = HttpMethod("GET")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", statePassword)
        }
        val csv = resp.bodyAsText()
        return State(csv.trim().ifEmpty { ";;" })
    }

    suspend fun storeState(state: State) {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$stateId") {
            method = HttpMethod("PUT")
            timeout {
                requestTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            basicAuth("anonymous", statePassword)
            setBody(state.data)
        }
        println(resp.bodyAsText())
    }

    /**
    # state: running|idle
    # updatedAt: <zoned-date-time>
    # processed: [<etag>\n, ...]
    <updatedAt>;<processed>
     */
    data class State(val data: String) {

        private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME
        private var state: String = data.split(";")[0].trim().ifEmpty { "idle" }
        private var updatedAt: ZonedDateTime? =
            data.split(";")[1].trim().ifEmpty { null }?.let { ZonedDateTime.parse(it, formatter) }
        private var etags: Set<String> = data.split(";")[2].split(",").toSet()

        fun isBusy(): Boolean {
            val timeout: Duration = 3.minutes
            return state == "running" && updatedAt?.plus(timeout.toJavaDuration())
                ?.isBefore(ZonedDateTime.now()) == true
        }

        fun start(): State {
            return State("running;${now()};${etags.joinToString(",")}")
        }

        fun unprocessed(candidates: Set<File>): SortedSet<File> {
            return candidates.filter { c -> etags.none { etag -> etag == c.etag } }
                .toSortedSet(compareBy { it.lastModified })
        }

        fun done(file: File): State {
            return State("running;${now()};${etags.joinToString(",")},${file.etag}")
        }

        fun done(): State {
            return State("idle;${now()};${etags.joinToString(",")}")
        }

        private fun now(): String? = formatter.format(ZonedDateTime.now())
    }

    data class File(val name: String, val etag: String, val lastModified: ZonedDateTime) {

    }
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

fun Application.receiptsModule() {
    configureHTTP()
    configureSerialization()
    configureRouting()
}

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
