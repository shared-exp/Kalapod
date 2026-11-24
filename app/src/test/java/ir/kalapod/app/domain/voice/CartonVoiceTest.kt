package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «۱۰ تا کارتن ۱۲ تایی کیک نادری» = 120 cakes in stock, shown as 10 cartons. */
class CartonVoiceTest {
    private val catalog = listOf(
        CatalogProduct(1, "کیک نادری", ProductUnit.PIECE, 50_000, 40_000),
        CatalogProduct(2, "کلوچه نادری", ProductUnit.PIECE, 60_000, 45_000),
        CatalogProduct(3, "نوشابه زمزم", ProductUnit.PIECE, 30_000, 25_000, caseSizeMilli = 6_000),
        CatalogProduct(4, "روغن لادن", ProductUnit.PIECE, 900_000, 800_000),
    )
    private fun buy(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, forPurchase = true)
    private fun item(s: String) = VoiceCommandParser.parse(s).items.single()

    @Test fun parsesCartonsAndContent() {
        for (s in listOf(
            "۱۰ تا کارتن ۱۲ تایی کیک نادری", "10 تا کارتن 12 تایی کیک نادری", "ده کارتن دوازده تایی کیک نادری",
            "۱۰ کارتن ۱۲تایی کیک نادری", "۱۰ کارتن کیک نادری ۱۲ تایی", "ده تا باکس ۱۲ عددی کیک نادری",
            "ده بُکس ۱۲ تایی کیک نادری", "ده بوکس ۱۲ تایی کیک نادری", "ده جعبه ۱۲ تایی کیک نادری", "ده تا کارتون ۱۲ تایی کیک نادری",
        )) {
            val i = item(s)
            assertEquals(s, "کیک نادری", i.name)
            assertEquals(s, 10_000L, i.quantityMilli)
            assertEquals(s, ProductUnit.CARTON, i.unit)
            assertEquals(s, 12_000L, i.caseSizeMilli)
        }
    }

    @Test fun cartonsBecomePiecesInStock() {
        val l = buy("۱۰ تا کارتن ۱۲ تایی کیک نادری خریدم").single()
        assertEquals(1L, l.product?.id)
        assertEquals(120_000L, l.quantityMilli)       // 120 pieces
        assertEquals(10_000L, l.casesMilli)           // shown as 10 cartons
        assertEquals(12_000L, l.caseSizeMilli)
        assertFalse(l.needsCaseSize)
    }

    @Test fun cartonSizeNotSaidIsAsked() {
        val l = buy("۱۰ تا کارتن کلوچه نادری خریدم").single()
        assertEquals(2L, l.product?.id)
        assertTrue(l.needsCaseSize)
        val answered = VoiceResolver.withCaseSize(l, 24_000, forPurchase = true)
        assertFalse(answered.needsCaseSize)
        assertEquals(240_000L, answered.quantityMilli)
        assertEquals(10_000L, answered.casesMilli)
    }

    @Test fun knownCartonSizeIsNotAskedAgain() {
        val l = buy("پنج کارتن نوشابه زمزم خریدم").single()
        assertFalse(l.needsCaseSize)
        assertEquals(30_000L, l.quantityMilli)
    }

    @Test fun cartonPrice() {
        val l = buy("۱۰ کارتن ۱۲ تایی کیک نادری کارتنی ۴۸۰ هزار تومان خریدم").single()
        assertEquals(4_800_000L, l.casePrice)            // rial per carton
        assertEquals(400_000L, l.unitPrice)              // per cake
        val t = buy("۱۰ کارتن ۱۲ تایی کیک نادری جمعا چهار میلیون و هشتصد خریدم").single()
        assertEquals(4_800_000L, t.casePrice)
    }

    @Test fun piecesStillWorkAndOtherItemsFollow() {
        val l = buy("۱۰ تا کارتن ۱۲ تایی کیک نادری دو تا روغن لادن خریدم")
        assertEquals(listOf(1L, 4L), l.map { it.product?.id })
        assertEquals(listOf(120_000L, 2_000L), l.map { it.quantityMilli })
        assertNull(l[1].casesMilli)
        // «تایی» before a number is still a per-piece price
        assertEquals(900_000L, buy("دو تا روغن لادن تایی ۹۰ هزار خریدم").single().unitPrice)
    }
}
