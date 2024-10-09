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
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.run.ThreadRunRequest
import com.aallam.openai.api.thread.ThreadMessage
import com.aallam.openai.api.thread.threadRequest
import com.aallam.openai.api.vectorstore.VectorStoreRequest
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import kotlinx.coroutines.delay
import okio.Buffer
import org.slf4j.LoggerFactory

/**
 * OpenAI client featuring methods to analyze file contents with following specializations:
 *  - extract line items and other metadata in a receipt of Migros or Coop
 *  - categorize an article name, e.g. "Steak" would be categorized as "Meat"
 */
class AiClient(
    private val token: String = requireNotNull(System.getenv("OPEN_AI_TOKEN")) { "OPEN_AI_TOKEN missing" },
    private val ai: OpenAI = OpenAI(config = OpenAIConfig(token = token))
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    @OptIn(BetaOpenAI::class)
    suspend fun categorize(itemNames: List<String>): List<String> {
        logger.info("Categorizing ${itemNames.size} item names")
        val assistantName = "asst_shopping_item_categorizer_v8"
        val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
            AssistantRequest(
                name = assistantName,
                model = ModelId("gpt-4o-mini"),
                instructions = """
        |You can categorize shopping items based on their german article name into one of the following categories:
        |Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck.
        |You may use other suitable category names if none of the suggested categories match.
        |""".trimMargin()
            )
        )

        val aiThreadRun = ai.createThreadRun(
            request = ThreadRunRequest(
                assistantId = assistant.id,
                thread = threadRequest {
                    messages = listOf(
                        ThreadMessage(
                            content = "Categorize each shopping item in the following list:\n${itemNames.joinToString("\n")}." +
                                    " Just output the same list again, but append the category name to each line. Use a comma as separator.",
                            role = Role.User
                        )
                    )
                }
            ))

        do {
            delay(1500)
            val retrievedRun = ai.getRun(threadId = aiThreadRun.threadId, runId = aiThreadRun.id)
        } while (retrievedRun.status != Status.Completed)

        val categoriesLine = ai.messages(aiThreadRun.threadId).map {
            it.content.first() as? MessageContent.Text ?: error("Expected MessageContent.Text")
        }
            .map { it.text.value }
            .first() // 1: categories, 2: the prompt

        val categories = categoriesLine.lines().map { it.split(",")[1].trim() }
        logger.info("Categorized ${itemNames.size} item names into ${categories.size} categories")
        return categories
    }

    @OptIn(BetaOpenAI::class)
    suspend fun extractCsv(content: Buffer, fileName: String): AnalysisResult {
        logger.info("extracting CSV from $fileName")
        val aiFile = ai.file(
            FileUpload(
                purpose = Purpose("assistants"),
                file = FileSource(name = fileName, source = content)
            )
        )
        val vectorStore = ai.createVectorStore(VectorStoreRequest(name = "receipts", fileIds = listOf(aiFile.id)))

        val assistantName = "asst_pdf_receipts_reader_v8"
        val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
            AssistantRequest(
                name = assistantName,
                model = ModelId("gpt-4o-mini"),
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
            .dropLast(1) // the prompt

        logger.info("Csv extracted from $fileName, line items: ${csv.size}")

        return AnalysisResult(csv = csv.joinToString("\n"))
    }
}
