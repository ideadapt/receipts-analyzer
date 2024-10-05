package net.ideadapt.plugins

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
import com.aallam.openai.api.vectorstore.VectorStore
import com.aallam.openai.api.vectorstore.VectorStoreRequest
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

//import io.ktor.server.sse.*
//import io.ktor.sse.*

@OptIn(BetaOpenAI::class)
fun Application.configureRouting() {
    //install(SSE)
    routing {
        get("/gists/{gist_id}") {
            val gistId = call.parameters["gist_id"]
            call.respondText("Hello $gistId")
        }
        post("/receipts") {
            val token = requireNotNull(System.getenv("OPEN_AI_TOKEN")) { "OPEN_AI_TOKEN missing" }
            val ai = OpenAI(config = OpenAIConfig(token = token))
            val multipartData = call.receiveMultipart()
            val buffer: okio.Buffer = okio.Buffer()
            val vectorStores = mutableListOf<VectorStore>()

            multipartData.forEachPart { part ->
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName as String
                            buffer.readFrom(part.streamProvider())
                            val aiFile = ai.file(
                                FileUpload(
                                    purpose = Purpose("assistants"),
                                    file = FileSource(name = fileName, source = buffer)
                                )
                            )
                            val vectorStore =
                                ai.createVectorStore(VectorStoreRequest(name = "receipts", fileIds = listOf(aiFile.id)))
                            vectorStores.add(vectorStore)
                        }

                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }

            val assistantName = "asst_pdf_receipts_reader_v7"
            val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
                AssistantRequest(
                    name = assistantName,
                    instructions = """
        |You can read tabular data from a shopping receipt and output this data in propper CSV format.
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
                            ToolResources(fileSearch = FileSearchResources(vectorStoreIds = vectorStores.map { it.id }))
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

            call.respond(FileAnalysisResult(csv = csv.joinToString("\n")))
        }
    }
}

@Serializable
data class FileAnalysisResult(val csv: String)
