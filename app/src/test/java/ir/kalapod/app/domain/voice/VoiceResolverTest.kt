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
        CatalogProduct(6, "رب گوجه فرنگی چین چین", ProductUnit.PIECE, 350_000, 300_000),
        CatalogProduct(7, "رب گوجه فرنگی تبرک", ProductUnit.PIECE, 330_000, 280_000),
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

    @Test fun misheardBrandStillFindsTheProduct() {
        val l = resolve("دو تا رب گوجه ارنگی شین شین فروختم").single()
        assertEquals(6L, l.product?.id)
        assertEquals(2_000L, l.quantityMilli)
    }

    @Test fun genericNameAlwaysAsksEvenIfAnsweredBefore() {
        val cmd = VoiceCommandParser.parse("یه دونه رب گوجه فرنگی فروختم")
        val first = VoiceResolver.resolve(cmd, catalog, false).single()
        assertTrue(first.needsChoice)
        assertTrue(first.generic)
        assertEquals(setOf(6L, 7L), first.options.map { it.id }.toSet())
        // Even with a remembered answer, a general name gets the question again — no default.
        val learned = mapOf(ProductMatcher.fold("رب گوجه فرنگی") to 7L)
        val again = VoiceResolver.resolve(cmd, catalog, false, learned).single()
        assertTrue(again.needsChoice)
        assertNull(again.product)
    }

    @Test fun specificNameNeedsNoQuestion() {
        val l = resolve("یه رب گوجه فرنگی تبرک فروختم").single()
        assertEquals(7L, l.product?.id)
    }

    @Test fun onlyOneTypeInStockIsNotGeneric() {
        val single = catalog.filter { it.id != 7L }
        val l = VoiceResolver.resolve(VoiceCommandParser.parse("یه رب گوجه فرنگی فروختم"), single, false).single()
        assertEquals(6L, l.product?.id)
    }
}
