package net.ideadapt

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.equals.shouldNotBeEqual
import kotlin.test.Test

class ApplicationTest {

    @Test
    fun `merge with empty`() {
        val existing = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult(
            csv = """
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.csv shouldBeEqual existing.csv
    }

    @Test
    fun `merge with just header`() {
        val existing = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.csv shouldBeEqual existing.csv
    }

    @Test
    fun `merge with just duplicate`() {
        val existing = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.csv shouldBeEqual existing.csv
    }

    @Test
    fun `merge with duplicate and new`() {
        val existing = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop            
            Zahnpasta xy,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.csv shouldNotBeEqual existing.csv
        merged.lineItems.map { it.articleName } shouldContainExactly listOf(
            "Bio Pagnolbrot dunkel 380G",
            "Zahnpasta xy"
        )
    }

    @Test
    fun `merge with new`() {
        val existing = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Bio Pagnolbrot dunkel 380G,1.0,3.40,3.40,Bäckerei,14.10.23 10:59,Coop
        """.trimIndent()
        )

        val new = AnalysisResult(
            csv = """
            Artikelbezeichnung,Menge,Preis,Total,Category,Datetime,Seller
            Zahnpasta xy,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
            WC Papier,1.0,3.40,3.40,Hygiene,14.10.23 10:59,Coop            
        """.trimIndent()
        )

        val merged = existing.merge(new)

        merged.csv shouldNotBeEqual existing.csv
        merged.lineItems.map { it.articleName } shouldContainExactly listOf(
            "Bio Pagnolbrot dunkel 380G",
            "Zahnpasta xy",
            "WC Papier"
        )
    }
}
