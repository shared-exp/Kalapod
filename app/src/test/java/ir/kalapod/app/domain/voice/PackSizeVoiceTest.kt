package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Products that differ only by pack size («۲۵۰ گرمی» vs «۵۰۰ گرمی») through the whole voice pipeline. */
class PackSizeVoiceTest {
    private fun p(id: Long, name: String, unit: ProductUnit = ProductUnit.PIECE) = CatalogProduct(id, name, unit, 400_000, 300_000, 5_000)
    private val catalog = listOf(
        p(1, "رب گوجه فرنگی چین چین ۲۵۰ گرمی"),
        p(2, "رب گوجه فرنگی چین چین ۵۰۰ گرمی"),
        p(3, "رب گوجه فرنگی تبرک ۸۰۰ گرمی"),
        p(4, "روغن لادن ۱.۵ لیتری"),
        p(5, "برنج هاشمی", ProductUnit.KILOGRAM),
    )
    private fun resolve(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, false)

    @Test fun sizeIsPartOfTheNameNotAQuantity() {
        val item = VoiceCommandParser.parse("دو تا رب چین چین ۵۰۰ گرمی فروختم").items.single()
        assertEquals(2_000L, item.quantityMilli)
        assertEquals("رب چین چین 500 گرمی", item.name)
    }

    @Test fun saidSizeGoesStraightToThatProduct() {
        val l = resolve("دو تا رب چین چین ۵۰۰ گرمی فروختم").single()
        assertEquals(2L, l.product?.id)
        assertEquals(2_000L, l.quantityMilli)
        assertEquals(1L, resolve("یه رب چین چین دویست و پنجاه گرمی فروختم").single().product?.id)
    }

    @Test fun noSizeAsksBetweenSizes() {
        val l = resolve("یه رب چین چین فروختم").single()
        assertTrue(l.needsChoice)
        assertTrue(l.generic)
        assertEquals(setOf(1L, 2L), l.options.map { it.id }.toSet())
    }

    @Test fun noBrandNoSizeAsksAmongAll() {
        val l = resolve("یه رب گوجه فرنگی فروختم").single()
        assertEquals(setOf(1L, 2L, 3L), l.options.map { it.id }.toSet())
    }

    @Test fun wrongSizeIsNeverMatched() {
        val l = resolve("یه رب چین چین ۸۰۰ گرمی فروختم").single()
        assertTrue(l.product?.id != 1L && l.product?.id != 2L)
    }

    @Test fun singleSizeProductStillFoundWithoutSize() {
        assertEquals(4L, resolve("دو تا روغن لادن فروختم").single().product?.id)
        assertEquals(4L, resolve("دو تا روغن لادن یک و نیم لیتری فروختم").single().product?.id)
    }

    @Test fun quantityInGramsStillWorksForWeighedGoods() {
        val l = resolve("پانصد گرم برنج هاشمی فروختم").single()
        assertEquals(5L, l.product?.id)
        assertEquals(500L, l.quantityMilli) // 0.5 kg
    }

    @Test fun answerTheQuestionBySize() {
        val opts = catalog.take(2)
        assertEquals(1L, TypeAnswer.pick("۲۵۰ گرمی", opts)?.id)
        assertEquals(2L, TypeAnswer.pick("پونصدی", opts)?.id)
        assertEquals(2L, TypeAnswer.pick("پونصد", opts)?.id)
        assertEquals(1L, TypeAnswer.pick("دویست و پنجاه", opts)?.id)
        assertEquals(1L, TypeAnswer.pick("کوچیکه", opts)?.id)
        assertEquals(2L, TypeAnswer.pick("بزرگه رو بده", opts)?.id)
        assertNull(TypeAnswer.pick("هزار گرمی", opts))
    }

    @Test fun ccHeardAsTwoWords() {
        assertEquals("نوشابه 300ml", PackSize.normalize(PersianText.tokens("نوشابه ۳۰۰ سی سی")).joinToString(" "))
    }
}
