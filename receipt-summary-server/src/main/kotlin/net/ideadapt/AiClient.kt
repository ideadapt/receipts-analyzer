package net.ideadapt

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.LogLevel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputFile
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.Tool
import okio.Buffer
import org.slf4j.LoggerFactory
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
        val params: ResponseCreateParams = ResponseCreateParams.builder().instructions(
            """
        |# Goal: Categorization of shopping line items.
        |# Input format:
        |Each input line has the format (placeholder names in < and >): <line-item-id>,<article-name>
        |Neither <line-item-id> nor <article-name> ever contains a coma. Coma is the delimiter.
        |# Categorization:
        |Categorize line items based on the article-name (german). Choose the best matching, german category:
        |Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck, Getreide, Kohlenhydrate, Gewürz, Kleidung, Haushalt, Elektronik.
        |Your last resort is a hyphen "-" symbol as category.
        |# Processing:
        |FOR EACH AND EVERY LINE: Output the EXACT input line and just append the category name after a comma (no whitespaces).
        |Make sure, that you DO NOT SKIP ANY LINE and do not modify the input!
        |""".trimMargin()
        )
            .addCodeInterpreterTool(Tool.CodeInterpreter.Container.CodeInterpreterToolAuto.builder().build())
            .input(itemNames.joinToString("\n"))
            .model("gpt-4o-mini")
            .build()

        val categoriesLines = ai.responses().create(params).output().stream()
            .flatMap { item -> item.message().stream() }
            .flatMap { message -> message.content().stream() }
            .flatMap { content -> content.outputText().stream() }
            .map { text -> text.text() }
            .collect(Collectors.toList()).first().lines()

        val articleCategories = categoriesLines.filter { !it.startsWith("```") }.map {
            // in some rare cases there is no category column, so we just fake one to not fail
            if (it.count { c -> c == ',' } == 1) {
                "$it,-"
            } else {
                it
            }.trim()
        }

        return articleCategories
    }

    fun extractLineItemsFromPDF(content: Buffer, fileName: String): AnalysisResult {
        logger.info("extracting line items from $fileName")
        val pdfData = Base64.getEncoder().encodeToString(content.readByteArray())
        val params = ResponseCreateParams.builder()
            .model("gpt-6-luna")
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
