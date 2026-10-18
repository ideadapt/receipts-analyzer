package net.ideadapt

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.LogLevel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputFile
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.Tool
import com.openai.models.uploads.UploadCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.stream.Collectors

/**
 * OpenAI client featuring methods to analyze file contents with following specializations:
 *  - extract line items and other metadata in a receipt of Migros or Coop
 *  - categorize an article name, e.g. "Steak" would be categorized as "Meat"
 */
class AiClient(
    private val token: String = requireNotNull(Config.get("OPEN_AI_TOKEN")) { "OPEN_AI_TOKEN missing" },
    private val ai: OpenAIClient = OpenAIOkHttpClient.builder().apiKey(token).logLevel(LogLevel.INFO).build()
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun categorize(itemNames: List<String>): List<String> {
        logger.info("categorizing ${itemNames.size} item names")

        val params: ResponseCreateParams = ResponseCreateParams.builder().instructions(
            """
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
            .input(itemNames.joinToString("\n"))
            .model("gpt-4o-mini")
            .build()

        val categoriesLines = ai.responses().create(params).output().stream()
            .flatMap { item -> item.message().stream() }
            .flatMap { message -> message.content().stream() }
            .flatMap { content -> content.outputText().stream() }
            .map { text -> text.text() }
            .collect(Collectors.toList()).first().lines()

        val articleCategories = categoriesLines.map {
            // in some rare cases there is no category column, so we just fake one to not fail
            if (it.count { c -> c == ',' } == 1) {
                "$it,-"
            } else {
                it
            }.trim()
        }

        logger.info("categorized ${itemNames.size} item names into ${articleCategories.size} categories")
        return articleCategories
    }

    fun extractLineItems(content: Buffer, fileName: String): AnalysisResult {
        logger.info("extracting line items from $fileName")
        val pdfData = Base64.getEncoder().encodeToString(content.readByteArray())
        val params = ResponseCreateParams.builder()
            .model("gpt-4o-mini")
            .inputOfResponse(
                listOf(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message.builder()
                            .role(ResponseInputItem.Message.Role.USER)
                            .addInputTextContent(
                                """
        |You can read tabular data from a german shopping receipt and output this data in proper CSV format.
        |You never include anything but the raw CSV rows. You omit the surrounding markdown code blocks.
        |Make sure you never remove the header row containing the column titles.
        |Add an extra column at the end called 'Datetime' that contains the literal date and time value found in the receipt (do not change the date format). The receipt date and time value is the same for every shopping item.
        |Add another extra column at the end called 'Seller' that contains the name of the receipt issuer (e.g. store name). The seller value is the same for every shopping item.
        |If the seller name contains one of: 'Migros', 'Coop', 'Aldi', 'Lidl', use that short form.
        |""".trimMargin()
                            )
                            .addContent(
                                ResponseInputFile.builder()
                                    .filename(fileName)
                                    .fileData("data:application/pdf;base64,$pdfData")
                                    .build()
                            )
                            .build()
                    )
                )
            )
            .addCodeInterpreterTool(Tool.CodeInterpreter.Container.CodeInterpreterToolAuto.builder().build())
            .build()

        val lineItemsCsv = ai.responses().create(params).output().stream()
            .flatMap { item -> item.message().stream() }
            .flatMap { message -> message.content().stream() }
            .flatMap { content -> content.outputText().stream() }
            .map { text -> text.text() }
            .collect(Collectors.toList()).first().lines()

        val lineItems = lineItemsCsv
            .drop(1) // header
            .map { lineItemCsv ->
                AnalysisResult.LineItem("$lineItemCsv,") // the last column (category) is empty
            }
            .toSet()

        logger.info("extracted ${lineItems.size} line items from $fileName")

        return AnalysisResult(lineItems)
    }
}
