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
        |A new line delimited list of CSV data (separator is a comma ","). Two columns per line: line-item-id, article-name. Column values are NOT quoted.
        |# Categorization:
        |Categorize line items based on the article-name (german). Choose the best matching, german category:
        |Frucht, Gemüse, Milchprodukt, Käse, Eier, Öl, Süssigkeit, Getränk, Alkohol, Fleisch, Fleischersatz, Gebäck, Getreide, Kohlenhydrate, Gewürz, Kleidung, Haushalt, Elektronik.
        |Your last resort is a hyphen "-" symbol as category.
        |Add the category at the end of the UNMODIFIED input line, separated by a comma ",".
        |DO NOT SKIP ANY INPUT LINE! Just output the modified input lines, no introduction text or code quotes etc.
        |""".trimMargin()
        )
            .addCodeInterpreterTool(Tool.CodeInterpreter.Container.CodeInterpreterToolAuto.builder().build())
            .input(itemNames.joinToString("\n"))
            .model("gpt-6-luna")
            .build()

        val categoriesLines = getLinesFromResponse(params)

        return categoriesLines.filter { !it.startsWith("```") }.map {
            // in some rare cases there is no category column, so we just fake one to not fail
            if (it.count { c -> c == ',' } == 1) {
                "$it,-"
            } else {
                it
            }.trim()
        }
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
        |Add another extra column at the end called 'Seller' that contains the name of the receipt issuer (e.g. grocery store name). The seller value is the same for every shopping item.
        |If the seller name contains one of: 'Migros', 'Coop', 'Aldi', 'Lidl', use that short form.
        |
        |PDFs from the seller "Coop" are structured as follows:
        |- Date and time at the top and at the very end of the PDF.
        |- The table has these columns: Artikel, Menge, Preis, Aktion, Total, Zusatz
        |- The table ends with a "Total CHF" amount
        |- For Coop, ignore the columns "Aktion" and "Zusatz", just include the column "Artikel", "Menge", "Preis", "Total" in the output CVS.
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

        val lineItemsCsv = getLinesFromResponse(params)

        val lineItems = lineItemsCsv
            .drop(1) // header
            .map { lineItemCsv ->
                AnalysisResult.LineItem("$lineItemCsv,") // the last column (category) is empty
            }
            .toSet()

        logger.info("extracted ${lineItems.size} line items from $fileName")

        return AnalysisResult(lineItems)
    }

    private fun getLinesFromResponse(params: ResponseCreateParams): List<String> = ai.responses().create(params).output().stream()
        .flatMap { item -> item.message().stream() }
        .flatMap { message -> message.content().stream() }
        .flatMap { content -> content.outputText().stream() }
        .map { text -> text.text() }
        .collect(Collectors.toList()).first().lines()
}
