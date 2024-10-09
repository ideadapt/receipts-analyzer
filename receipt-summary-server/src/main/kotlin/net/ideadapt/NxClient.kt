package net.ideadapt

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
    private val nxRoot: String = "https://ideadapt.net/nextcloud"
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    suspend fun files(): SortedSet<File> {
        logger.info("getting files")
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId") {
            applyRequestParams("PROPFIND", password = sharePassword)
        }

        if (!resp.status.isSuccess()) {
            logger.error(
                "Error getting files of folder $shareId. status: {}, body: {}",
                resp.status,
                resp.bodyAsText().take(1000)
            )
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
                            lastModified = ZonedDateTime.parse(f.prop.getLastModified, nxFormatter),
                            contentType = f.prop.getContentType
                        )
                    )
                }
            }

        logger.info("getting files: found ${files.size} files")

        return files.toSortedSet(compareBy { it.lastModified })
    }

    suspend fun file(fileName: String): Buffer {
        logger.info("getting file $fileName")
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId/${fileName}") {
            applyRequestParams("GET", password = sharePassword)
        }

        if (!resp.status.isSuccess()) {
            logger.error(
                "Error getting files of folder $shareId. status: {}, body: {}",
                resp.status,
                resp.bodyAsText().take(200)
            )
            throw IllegalStateException(
                String.format(
                    "Error getting file $$shareId/$fileName. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText().take(1000)
                )
            )
        }
        logger.info("found file $fileName, size: ${resp.headers["Content-Length"]} bytes")

        return Buffer().readFrom(resp.bodyAsChannel().toInputStream())
    }

    suspend fun state(): State {
        logger.info("getting state")
        val resp = client.request("$nxRoot/public.php/dav/files/$stateId") {
            applyRequestParams("GET", password = statePassword)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error getting state $stateId. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText().take(1000)
                )
            )
        }
        logger.info("found state, size: ${resp.headers["Content-Length"]} bytes")

        val csv = resp.bodyAsText()
        return State(csv)
    }

    suspend fun storeState(state: State) {
        logger.info("storing state ${state.csv.take(50)}...")
        val resp = client.request("$nxRoot/public.php/dav/files/$stateId") {
            applyRequestParams("PUT", password = statePassword)
            setBody(state.csv)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error storing state $stateId. status: %s, body: %s ...",
                    resp.status,
                    resp.bodyAsText().take(1000)
                )
            )
        }
        logger.info("stored state")
    }

    suspend fun analyzed(): AnalysisResult {
        logger.info("getting analyzed")
        val resp = client.request("$nxRoot/public.php/dav/files/$analyzedId") {
            applyRequestParams("GET", password = analyzedPassword)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error getting analyzed $analyzedId. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText().take(1000)
                )
            )
        }
        logger.info("found analyzed, size: ${resp.headers["Content-Length"]} bytes")

        return AnalysisResult(resp.bodyAsText())
    }

    suspend fun storeAnalysisResult(analysis: AnalysisResult) {
        logger.info("storing analysis result ...${analysis.csv.takeLast(50)}")
        val resp = client.request("$nxRoot/public.php/dav/files/$analyzedId") {
            applyRequestParams("PUT", password = analyzedPassword)
            setBody(analysis.csv)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "Error storing analysis result ${analysis.csv.takeLast(50)}. status: %s, body: %s ...",
                    resp.status,
                    resp.bodyAsText().take(1000)
                )
            )
        }
        logger.info("stored analysis result")
    }

    private fun HttpRequestBuilder.applyRequestParams(
        theMethod: String,
        username: String = "anonymous",
        password: String
    ) {
        method = HttpMethod(theMethod)
        basicAuth(username, password)
        timeout {
            requestTimeoutMillis = 20.seconds.inWholeMilliseconds
        }
        retry {
            maxRetries = 1
            constantDelay(1000)
        }
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

    data class File(val name: String, val etag: String, val lastModified: ZonedDateTime, val contentType: String?)
}

fun parseXml(xml: String): Multistatus {
    val xmlParser = XML {
        autoPolymorphic = false
    }

    return xmlParser.decodeFromString(xml)
}

// example response
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
