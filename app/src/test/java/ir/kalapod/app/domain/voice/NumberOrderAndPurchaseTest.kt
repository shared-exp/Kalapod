package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberOrderAndPurchaseTest {
    private fun p(id: Long, name: String) = CatalogProduct(id, name, ProductUnit.PIECE, 400_000, 300_000, 5_000)
    private val catalog = listOf(
        p(1, "رب گوجه فرنگی چین چین ۷۰۰ گرمی"),
        p(2, "رب گوجه فرنگی چین چین ۲۵۰ گرمی"),
        p(3, "روغن لادن ۱.۵ لیتری"),
        p(4, "روغن لادن ۸۱۰ گرمی"),
    )
    private fun sale(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, forPurchase = false)
    private fun purchase(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, forPurchase = true)

    // ---- «۱۰ تا رب ۷۰۰ چین چین» = «۱۰ تا رب چین چین ۷۰۰»

    @Test fun sizeBeforeOrAfterTheBrandIsTheSame() {
        for (s in listOf("۱۰ تا رب ۷۰۰ چین چین فروختم", "۱۰ تا رب چین چین ۷۰۰ فروختم", "ده تا رب چین چین هفتصد فروختم")) {
            val l = sale(s).single()
            assertEquals(s, 1L, l.product?.id)
            assertEquals(s, 10_000L, l.quantityMilli)
        }
    }

    @Test fun nextItemStartsAtQuantityWithUnit() {
        val lines = sale("۱۰ تا رب چین چین ۷۰۰ ۲۰ تا روغن لادن ۱.۵ فروختم")
        assertEquals(2, lines.size)
        assertEquals(1L, lines[0].product?.id)
        assertEquals(10_000L, lines[0].quantityMilli)
        assertEquals(3L, lines[1].product?.id)       // 1.5 said without «لیتری» still means the 1.5 L bottle
        assertEquals(20_000L, lines[1].quantityMilli)
    }

    @Test fun spokenWordsToo() {
        val lines = sale("ده تا رب چین چین هفتصد بیست تا روغن لادن یک و نیم فروختم")
        assertEquals(listOf(1L, 3L), lines.map { it.product?.id })
        assertEquals(listOf(10_000L, 20_000L), lines.map { it.quantityMilli })
        // compound numbers still work
        assertEquals(2_500_000L, SpokenNumbers.parse(PersianText.tokens("دو هزار پونصد"), 0)!!.milli)
        assertEquals(720_000L, SpokenNumbers.parse(PersianText.tokens("هفتصد و بیست"), 0)!!.milli)
    }

    @Test fun bareSizeChoosesBetweenSizes() {
        assertEquals(2L, sale("یه رب چین چین ۲۵۰ فروختم").single().product?.id)
        assertEquals(4L, sale("دو تا روغن لادن ۸۱۰ فروختم").single().product?.id)
    }

    @Test fun pricesAreStillPrices() {
        val l = sale("دو تا روغن لادن ۱.۵ دونه‌ای ۹۰ هزار فروختم").single()
        assertEquals(3L, l.product?.id)
        assertEquals(900_000L, l.unitPrice)
    }

    // ---- purchases don't force-match: it may be a new product

    @Test fun purchaseOfMisheardNameAsksInsteadOfGuessing() {
        val saleLine = sale("ده تا رب گوجه ارنگی شین شین ۷۰۰ فروختم").single()
        assertEquals(1L, saleLine.product?.id)                  // sale: sound-alike matching as before
        val buy = purchase("ده تا رب گوجه ارنگی شین شین ۷۰۰ خریدم").single()
        assertNull(buy.product)                                 // purchase: confirm or say it's new
        assertTrue(buy.allowNew)
        assertEquals(1L, buy.options.first().id)
    }

    @Test fun purchaseOfExactNameNeedsNoQuestion() {
        assertEquals(1L, purchase("ده تا رب گوجه فرنگی چین چین ۷۰۰ گرمی خریدم").single().product?.id)
    }

    @Test fun purchaseOfUnknownProductOffersToDefineIt() {
        val l = purchase("پنج کارتن نوشابه زمزم خریدم").single()
        assertTrue(l.isUnknown || l.allowNew)
        assertNull(l.product)
    }
}
