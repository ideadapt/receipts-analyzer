package net.ideadapt

import kotlin.test.Test
import kotlin.test.asserter

class ApplicationTest {

    @Test
    fun merge() {
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

        asserter.assertTrue("", merged.csv == existing.csv)
    }
}
