package net.ideadapt

import io.ktor.client.*
import io.ktor.client.engine.cio.*
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
            val nx = NxClient()
            var latestProcessed: ZonedDateTime? = null
            while (true) {
                if (nx.modifiedSince(latestProcessed)) { // TODO storeState modifies the folder, move to another folder?
                    val state = nx.state()
                    if (!state.isBusy()) {
                        val candidateFiles = nx.files()
                        state.unprocessed(candidateFiles).firstOrNull()?.let { nextFile ->
                            // ai.analyze(file)
                            val newState = state.done(nextFile)
                            nx.storeState(newState)

                            // not very robust, since file modification time can be cheated
                            latestProcessed = nextFile.lastModified
                        } ?: {
                            latestProcessed = ZonedDateTime.now()
                        }
                    }
                }

                delay(5.seconds)
            }
        }
    }
}

data class NxClient(
    private val shareId: String = requireNotNull(System.getenv("SHARE_ID")) { "SHARE_ID missing" },
    private val sharePassword: String = requireNotNull(System.getenv("SHARE_PASSWORD")) { "SHARE_PASSWORD missing" },
    private val client: HttpClient = HttpClient(CIO),
    private val nxFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z")
) {

    suspend fun modifiedSince(since: ZonedDateTime?): Boolean {
        if (since == null) return true

        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId") {
            method = HttpMethod("PROPFIND")
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

    suspend fun state(): State {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId/state.csv") {
            method = HttpMethod("GET")
            basicAuth("anonymous", sharePassword)
        }
        val csv = resp.bodyAsText()
        return State(csv)
    }

    suspend fun files(): SortedSet<File> {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId") {
            method = HttpMethod("PROPFIND")
            basicAuth("anonymous", sharePassword)
        }
        val xml = resp.bodyAsText()
        val parsedXml = parseXml(xml)
        val files = mutableSetOf<File>()

        parsedXml.responses
            .filter { r -> !r.href.endsWith("/state.csv") }
            .forEach { r ->
                val folderFiles = r.propstats
                    .filter { p -> p.prop.quotaUsedBytes == null }
                folderFiles.forEach { f ->
                    files.add(
                        File(
                            name = r.href,
                            state = "",
                            lastModified = ZonedDateTime.parse(f.prop.getLastModified, nxFormatter)
                        )
                    )
                }
            }

        return files.toSortedSet(compareBy { it.lastModified })
    }

    suspend fun storeState(state: State) {
        val resp = client.request("https://ideadapt.net/nextcloud/public.php/dav/files/$shareId/state.csv") {
            method = HttpMethod("PUT")
            basicAuth("anonymous", sharePassword)
            setBody(state.csv)
        }
        println(resp.bodyAsText())
    }

    data class State(val csv: String) {

        private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME
        private var files: Set<File> = csv.lines().drop(1).map { line ->
            val (lastModified, state, file) = line.split(",")
            File(file, state, ZonedDateTime.parse(lastModified, formatter))
        }.toSet()

        fun isBusy(): Boolean {
            return false
        }

        fun unprocessed(candidates: Set<File>): Set<File> {
            return candidates.filter { c -> files.none { f -> f.name == c.name } }
                .toSortedSet(compareBy { it.lastModified })
        }

        fun done(file: File): State {
            return State(csv + "\n${ZonedDateTime.now()},done,${file.name}")
        }
    }

    data class File(val name: String, val state: String, val lastModified: ZonedDateTime) {

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
    val getEtag: String? = null,

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
