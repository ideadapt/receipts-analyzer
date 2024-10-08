package net.ideadapt.plugins

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import net.ideadapt.AiClient
import net.ideadapt.NxClient.File
import net.ideadapt.sync
import org.intellij.lang.annotations.Language
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

@Language("JSON")
val x = """ 
{
  "eventType": "OCA\\WorkflowEngine\\Entity\\File",
  "eventName": "\\OCP\\Files::postCreate",
  "node": {
    "id": 249291,
    "storage": {
      "cache": null,
      "scanner": {},
      "watcher": null,
      "propagator": null,
      "updater": {}
    },
    "path": "\/uk-phone\/files\/receipts\/Receipt_20240718_151446_0090466_003_347.pdf",
    "internalPath": "files\/receipts\/Receipt_20240718_151446_0090466_003_347.pdf",
    "modifiedTime": 1727524714,
    "mimeType": "application\/pdf",
    "size": 129191,
    "Etag": "fd0cbab8e54363c9de8567bbe924c35f",
    "permissions": 27,
    "isUpdateable": true,
    "isDeletable": true,
    "isShareable": true
  },
  "workflowFile": {
    "displayText": "... created Receipt_20240718_151446_0090466_003_347.pdf",
    "url": "https:\/\/ideadapt.net\/nextcloud\/index.php\/f\/249291"
  }
}
"""

@Serializable
data class FlowEventFileCreate(
    val eventName: String,
    val node: FileNode,
)

@Serializable
data class FileNode @OptIn(ExperimentalSerializationApi::class) constructor(
    val internalPath: String,
    val modifiedTime: Long,
    val mimeType: String,
    val size: Long,
    @JsonNames("Etag")
    val etag: String,
)

private val json = Json {
    ignoreUnknownKeys = true
}

fun Application.configureRouting() {

    routing {
        post("/hooks") {
            // TODO https://github.com/kffl/nextcloud-webhooks/blob/master/README.md#authenticating-requests
            val body = call.receiveText()
            call.application.environment.log.info("Received hook {}", body)
            if (body.contains("\\\\OCP\\\\Files::postCreate")) {
                val event = json.decodeFromString<FlowEventFileCreate>(body)

                launch(Dispatchers.IO) { // run in dedicated thread pool, pool size = nr of CPUs
                    val lastModified =
                        ZonedDateTime.ofInstant(Date(event.node.modifiedTime * 1000L).toInstant(), ZoneId.of("UTC"))
                    sync(
                        File(
                            name = event.node.internalPath.substringAfterLast("/"),
                            lastModified = lastModified,
                            etag = event.node.etag
                        )
                    )
                }

                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respond(HttpStatusCode.NotImplemented)
            }
        }

        post("/receipts") {
            val multipartData = call.receiveMultipart()
            val buffer: okio.Buffer = okio.Buffer()

            multipartData.forEachPart { part ->
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName as String
                            buffer.readFrom(part.streamProvider())

                            val aiClient = AiClient()
                            aiClient.analyze(buffer, fileName)
                        }

                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }

            call.respondText("TODO")
        }
    }
}

@Serializable
data class FileAnalysisResult(val csv: String)
