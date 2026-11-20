package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceResolverTest {
    private val catalog = listOf(
        CatalogProduct(1, "روغن لادن", ProductUnit.PIECE, 1_000_000, 900_000),
        CatalogProduct(2, "برنج هاشمی", ProductUnit.KILOGRAM, 2_000_000, 1_800_000),
        CatalogProduct(3, "برنج طارم", ProductUnit.KILOGRAM, 1_500_000, 1_300_000),
        CatalogProduct(4, "ماکارونی زر", ProductUnit.PACK, 300_000, 250_000),
        CatalogProduct(5, "زعفران", ProductUnit.GRAM, 50_000, 40_000),
    )

    private fun resolve(s: String, purchase: Boolean = false) =
        VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, purchase)

    @Test fun specExample_multiItemSale() {
        val lines = resolve("سه تا روغن و دو بسته ماکارونی فروختم")
        assertEquals(listOf(1L, 4L), lines.map { it.product?.id })
        assertEquals(listOf(3_000L, 2_000L), lines.map { it.quantityMilli })
        assertTrue(lines.all { it.unitPrice == null }) // product defaults will be used
    }

    @Test fun spokenPriceKept() {
        val l = resolve("ده کیلو برنج هاشمی خریدم کیلویی ۱۸۰ هزار تومان", purchase = true).single()
        assertEquals(2L, l.product?.id)
        assertEquals(10_000L, l.quantityMilli)
        assertEquals(1_800_000L, l.unitPrice)
    }

    @Test fun gramsConvertedToKilos() {
        val l = resolve("پانصد گرم برنج هاشمی فروختم").single()
        assertEquals(500L, l.quantityMilli) // 0.5 kg
    }

    @Test fun perKiloPriceConvertedForGramProduct() {
        val l = resolve("ده گرم زعفران فروختم کیلویی ۵۰ میلیون").single()
        assertEquals(10_000L, l.quantityMilli)
        assertEquals(500_000L, l.unitPrice) // 500,000,000 rial/kg → 500,000 rial/g
    }

    @Test fun totalPriceBecomesUnitPrice() {
        val l = resolve("دو کیلو برنج هاشمی جمعا ۴۰۰ هزار تومان").single()
        assertEquals(2_000_000L, l.unitPrice)
    }

    @Test fun ambiguousAndUnknown() {
        val lines = resolve("یک کیلو برنج و دو تا پودر لباسشویی فروختم")
        assertTrue(lines[0].needsChoice)
        assertEquals(setOf(2L, 3L), lines[0].options.map { it.id }.toSet())
        assertTrue(lines[1].isUnknown)
        assertNull(lines[1].product)

        val chosen = VoiceResolver.choose(lines[0], catalog[2], forPurchase = false)
        assertEquals(3L, chosen.product?.id)
        assertEquals(1_000L, chosen.quantityMilli)
    }
}
