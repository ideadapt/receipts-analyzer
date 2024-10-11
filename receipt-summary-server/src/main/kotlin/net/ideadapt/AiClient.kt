package net.ideadapt

import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.assistant.AssistantRequest
import com.aallam.openai.api.assistant.AssistantTool
import com.aallam.openai.api.assistant.FileSearchResources
import com.aallam.openai.api.assistant.ToolResources
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.core.Status
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.file.FileUpload
import com.aallam.openai.api.file.Purpose
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.message.MessageContent
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.run.ThreadRunRequest
import com.aallam.openai.api.thread.ThreadMessage
import com.aallam.openai.api.thread.threadRequest
import com.aallam.openai.api.vectorstore.ExpirationPolicy
import com.aallam.openai.api.vectorstore.VectorStoreRequest
import com.aallam.openai.client.LoggingConfig
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
    private val ai: OpenAI = OpenAI(
        config = OpenAIConfig(
            token = token,
            logging = LoggingConfig(LogLevel.Info, Logger.Default),
        )
    )
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    @OptIn(BetaOpenAI::class)
    suspend fun categorize(itemNames: List<String>): List<String> {
        logger.info("categorizing ${itemNames.size} item names")
        val assistantName = "asst_shopping_item_categorizer_v16"
        val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
            AssistantRequest(
                name = assistantName,
                model = ModelId("gpt-4o-mini"),
                instructions = """
        |You can categorize shopping items based on their german article name into one of the following categories:
        |Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck.
        |You may use other suitable category names if none of the suggested categories match.
        |If you can not figure out a good category, think about a suitable category name again. 
        |If you still can't figure it out your last resort is to use a hyphen "-" symbol.
        |Each line in the input has the following format (using placeholder names in < and >): <technical-prefix>,<article-name>
        |Where neither <technical-prefix> nor <article-name> can contain a coma. So the coma is the column delimiter.
        |To process the user input, do the following for each and every line: Output the exact input line again and just append the category name after a comma.
        |Make sure, that you do not skip any line!
        |Omit introduction sentences or the like.
        |""".trimMargin()
            )
        )

        val aiThreadRun = ai.createThreadRun(
            request = ThreadRunRequest(
                assistantId = assistant.id,
                thread = threadRequest {
                    messages = listOf(
                        ThreadMessage(
                            content = itemNames.joinToString("\n"),
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

        val articleCategories = categoriesLine.lines().map {
            // in some rare cases there is no category column, so we just fake one to not fail
            if (it.count { c -> c == ',' } == 1) {
                "$it,-"
            } else {
                it
            }.trim()
        }

        ai.delete(aiThreadRun.threadId)

        logger.info("categorized ${itemNames.size} item names into ${articleCategories.size} categories")
        return articleCategories
    }

    @OptIn(BetaOpenAI::class)
    suspend fun extractLineItems(content: Buffer, fileName: String): AnalysisResult {
        logger.info("extracting line items from $fileName")
        val aiFile = ai.file(
            FileUpload(
                purpose = Purpose("assistants"),
                file = FileSource(name = fileName, source = content)
            )
        )
        val vectorStore = ai.createVectorStore(
            VectorStoreRequest(
                name = "receipt",
                fileIds = listOf(aiFile.id),
                expiresAfter = ExpirationPolicy(days = 1, anchor = "last_active_at")
            )
        )

        val assistantName = "asst_pdf_receipts_reader_v14"
        val assistant = ai.assistants().find { it.name == assistantName } ?: ai.assistant(
            AssistantRequest(
                name = assistantName,
                model = ModelId("gpt-4o-mini"),
                tools = listOf(AssistantTool.FileSearch),
                instructions = """
        |You can read tabular data from a german shopping receipt and output this data in proper CSV format.
        |You never include anything but the raw CSV rows. You omit the surrounding markdown code blocks.
        |Make sure you never remove the header row containing the column titles.
        |Add an extra column at the end called 'Datetime' that contains the literal date and time value found in the receipt (do not change the date format). The receipt date and time value is the same for every shopping item.
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

        val lineItems = ai.messages(aiThreadRun.threadId).map {
            it.content.first() as? MessageContent.Text ?: error("Expected MessageContent.Text")
        }
            .map { it.text.value }
            .dropLast(1) // the prompt
            .flatMap { lineItemsCsv ->
                lineItemsCsv
                    .lines()
                    .drop(1) // header
                    .map { lineItemCsv ->
                        AnalysisResult.LineItem("$lineItemCsv,") // the last column (category) is empty
                    }
            }
            .toSet()

        ai.delete(aiFile.id)
        ai.delete(vectorStore.id)
        ai.delete(aiThreadRun.threadId)

        logger.info("extracted ${lineItems.size} line items from $fileName")

        return AnalysisResult(lineItems)
    }
}
