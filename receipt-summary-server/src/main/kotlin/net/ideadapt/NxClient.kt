package net.ideadapt

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
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
    private val shareId: String = requireNotNull(Config.get("SHARE_ID")) { "SHARE_ID missing" },
    private val sharePassword: String = requireNotNull(Config.get("SHARE_PASSWORD")) { "SHARE_PASSWORD missing" },
    private val stateId: String = requireNotNull(Config.get("STATE_ID")) { "STATE_ID missing" },
    private val statePassword: String = requireNotNull(Config.get("STATE_PASSWORD")) { "STATE_PASSWORD missing" },
    private val analyzedId: String = requireNotNull(Config.get("ANALYZED_ID")) { "ANALYZED_ID missing" },
    private val analyzedPassword: String = requireNotNull(Config.get("ANALYZED_PASSWORD")) { "ANALYZED_PASSWORD missing" },
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout)
    },
    private val nxFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
    private val nxRoot: String = requireNotNull(Config.get("NX_ROOT")) { "NX_ROOT missing" },
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    suspend fun files(): List<File> {
        logger.debug("getting files")
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId") {
            applyRequestParams("PROPFIND", password = sharePassword)
        }

        if (!resp.status.isSuccess()) {
            logger.error(
                "error getting files of folder $shareId. status: {}, body: {}",
                resp.status,
                resp.bodyAsText().take(5000)
            )
            return listOf()
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

        logger.info("found ${files.size} files")

        return files.sortedBy { it.lastModified }
    }

    suspend fun readFile(fileName: String): Buffer {
        logger.debug("getting file $fileName")
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId/${fileName}") {
            applyRequestParams("GET", password = sharePassword)
        }

        if (!resp.status.isSuccess()) {
            logger.error(
                "error getting file '$fileName' of folder $shareId. status: {}, body: {}",
                resp.status,
                resp.bodyAsText().take(200)
            )
            throw IllegalStateException(
                String.format(
                    "error getting file $$shareId/$fileName. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText().take(5000)
                )
            )
        }
        logger.info("found file $fileName, size: ${resp.headers["Content-Length"]} bytes")

        return Buffer().readFrom(resp.bodyAsChannel().toInputStream())
    }

    suspend fun saveFile(buffer: Buffer, fileName: String): Boolean {
        val size = buffer.size
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId/$fileName") {
            applyRequestParams("PUT", password = sharePassword)
            contentType(ContentType.fromFileExtension(fileName).first())
            setBody(ByteArrayContent(buffer.readByteArray(), ContentType.Application.OctetStream))
        }

        if (!resp.status.isSuccess()) {
            logger.error("error storing file $fileName, size: $size, response: {}", resp.bodyAsText())
            return false
        } else {
            logger.info("stored file $fileName, size: $size")
        }
        return true
    }

    suspend fun rename(file: File, to: String): Boolean {
        val resp = client.request("$nxRoot/public.php/dav/files/$shareId/${file.name}") {
            applyRequestParams("MOVE", password = sharePassword)
            header("Destination", "$nxRoot/public.php/dav/files/$shareId/$to")
        }

        if (!resp.status.isSuccess()) {
            logger.error("error renaming file ${file.name} to $to, response: {}", resp.bodyAsText())
            return false
        } else {
            logger.info("renamed file ${file.name} to $to")
        }
        return true
    }

    suspend fun analyzed(): AnalysisResult {
        logger.debug("getting analyzed")
        val resp = client.request("$nxRoot/public.php/dav/files/$analyzedId") {
            applyRequestParams("GET", password = analyzedPassword)
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "error getting analyzed $analyzedId. status: %s, body: %s",
                    resp.status,
                    resp.bodyAsText().take(5000)
                )
            )
        }
        logger.info("found analyzed, size: ${resp.headers["Content-Length"]} bytes")

        return AnalysisResult.fromCsvWithHeader(resp.bodyAsText())
    }

    suspend fun saveAnalysisResult(analysis: AnalysisResult) {
        logger.debug("storing analysis result with ${analysis.lineItems.size} items")
        val resp = client.request("$nxRoot/public.php/dav/files/$analyzedId") {
            applyRequestParams("PUT", password = analyzedPassword)
            setBody(analysis.toString())
        }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException(
                String.format(
                    "error storing analysis result. status: %s, body: %s...",
                    resp.status,
                    resp.bodyAsText().take(5000)
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
