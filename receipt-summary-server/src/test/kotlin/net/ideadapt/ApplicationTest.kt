package net.ideadapt

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.equals.shouldNotBeEqual
import okio.Buffer
import kotlin.test.Test

class ApplicationTest {

    @Test
    fun `merge with empty`() {
        val existing = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult.fromCsvWithHeader(
            csv = """
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.lineItems shouldBeEqual existing.lineItems
    }

    @Test
    fun `merge with just header`() {
        val existing = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.lineItems shouldBeEqual existing.lineItems
    }

    @Test
    fun `merge with just duplicate`() {
        val existing = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.lineItems shouldBeEqual existing.lineItems
    }

    @Test
    fun `merge with duplicate and new`() {
        val existing = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop            
            Zahnpasta xy,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.lineItems shouldNotBeEqual existing.lineItems
        merged.lineItems.map { it.articleName } shouldContainExactly listOf(
            "Bio Pagnolbrot dunkel 380G",
            "Zahnpasta xy"
        )
    }

    @Test
    fun `merge with new`() {
        val existing = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult.fromCsvWithHeader(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Zahnpasta xy,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
            WC Papier,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.lineItems shouldNotBeEqual existing.lineItems
        merged.lineItems.map { it.articleName } shouldContainExactly listOf(
            "Bio Pagnolbrot dunkel 380G",
            "Zahnpasta xy",
            "WC Papier"
        )
    }

    @Test
    fun `convert migros CSV to AnalysisResult`() {
        val csv = """
        Datum;Zeit;Filiale;Kassennummer;Transaktionsnummer;Artikel;Menge;Aktion;Umsatz
        05.09.2024;12:50:16;MM Gäuggelistrasse;267;81;Alnatura Reiswaffel;0.235;0.00;1.95
        """.trimIndent()

        val result = MigrosCsv(buffer = Buffer().writeUtf8(csv)).toAnalysisResult()
        result.toString() shouldBeEqual """
            Artikelbezeichnung,Menge,Preis,Total,Datetime,Seller,Category
            Alnatura Reiswaffel,0.235,0.46,1.95,2024-09-05T12:50:16,Migros,
        """.trimIndent().trim()
    }
}
