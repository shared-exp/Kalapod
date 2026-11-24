package ir.kalapod.app.domain.voice

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several items said back to back, with or without «و», units, sizes and prices. */
class ConsecutiveItemsTest {
    private fun p(id: Long, name: String) = CatalogProduct(id, name, ProductUnit.PIECE, 400_000, 300_000, 5_000)
    private val catalog = listOf(
        p(1, "روغن لادن ۱.۵ لیتری"),
        p(2, "پنیر صبا"),
        p(3, "رب گوجه فرنگی چین چین ۷۰۰ گرمی"),
        p(4, "ماست کاله"),
        p(5, "نوشابه زمزم"),
        p(6, "رب گوجه فرنگی چین چین ۲۵۰ گرمی"),
    )

    private fun items(s: String) = VoiceCommandParser.parse(s).items.map { it.name to it.quantityMilli }
    private fun ids(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), catalog, forPurchase = false).map { it.product?.id to it.quantityMilli }

    @Test fun theReportedCase() {
        assertEquals(listOf("روغن لادن" to 1_000L, "پنیر صبا" to 1_000L), items("یه روغن لادن یه پنیر صبا"))
        assertEquals(listOf(1L to 1_000L, 2L to 1_000L), ids("یه روغن لادن یه پنیر صبا"))
    }

    @Test fun manyItemsInARow() {
        assertEquals(
            listOf(1L to 1_000L, 2L to 2_000L, 4L to 3_000L, 5L to 1_000L),
            ids("یه روغن لادن دو پنیر صبا سه ماست کاله یه نوشابه زمزم فروختم"),
        )
    }

    @Test fun digitsInsteadOfWords() {
        assertEquals(listOf(1L to 1_000L, 2L to 2_000L), ids("1 روغن لادن 2 پنیر صبا"))
    }

    @Test fun withAndOrUnitsOrCommas() {
        val want = listOf(1L to 1_000L, 2L to 2_000L)
        assertEquals(want, ids("یه روغن لادن و دو تا پنیر صبا"))
        assertEquals(want, ids("یک عدد روغن لادن دو عدد پنیر صبا"))
        assertEquals(want, ids("یه روغن لادن، دو پنیر صبا"))
        assertEquals(want, ids("یه دونه روغن لادن دوتا پنیر صبا"))
    }

    @Test fun firstItemWithoutQuantity() {
        assertEquals(listOf(1L to null, 2L to 2_000L), ids("روغن لادن دو پنیر صبا").map { it.first to it.second?.takeIf { q -> q != 1_000L || it.first == 2L } })
        assertEquals(2, ids("روغن لادن یه پنیر صبا").size)
    }

    @Test fun sizesStayPartOfTheName() {
        assertEquals(listOf(3L to 10_000L), ids("۱۰ تا رب ۷۰۰ چین چین"))
        assertEquals(listOf(6L to 1_000L), ids("یه رب چین چین ۲۵۰"))
        assertEquals(listOf(1L to 2_000L), ids("دو تا روغن لادن ۱.۵"))
        assertEquals(listOf(1L to 2_000L), ids("دو تا روغن لادن یک و نیم لیتری"))
        // size then the next item
        assertEquals(listOf(3L to 1_000L, 2L to 2_000L), ids("یه رب چین چین ۷۰۰ دو پنیر صبا"))
        assertEquals(listOf(1L to 1_000L, 2L to 1_000L), ids("یه روغن لادن ۱.۵ یه پنیر صبا"))
    }

    @Test fun pricesAreNotCounts() {
        val l = VoiceCommandParser.parse("دو تا پنیر صبا دونه‌ای ۹۰ هزار یه روغن لادن").items
        assertEquals(listOf("پنیر صبا", "روغن لادن"), l.map { it.name })
        assertEquals(900_000L, l[0].unitPrice)
        val t = VoiceCommandParser.parse("یه روغن لادن ۲۰ تومن یه پنیر صبا").items
        assertEquals(listOf("روغن لادن", "پنیر صبا"), t.map { it.name })
    }

    @Test fun trailingSmallNumberStaysWithItsItem() {
        // Nothing after it: not a new item.
        assertEquals(1, items("یه روغن لادن ۲").size)
    }

    /** Reported: speech recognition heard «یه ربع بعد» between two items. */
    @Test fun misheardMiddleItemStaysSeparate() {
        assertEquals(
            listOf("روغن لادن" to 1_000L, "ربع بعد" to 1_000L, "پنیر صبا" to 1_000L),
            items("یه روغن لادن یه ربع بعد یه پنیر صبا"),
        )
        val ids = ids("یه روغن لادن یه ربع بعد یه پنیر صبا")
        assertEquals(1L, ids.first().first)
        assertEquals(2L, ids.last().first)
    }

    @Test fun internalSizeMarkersAreNeverShown() {
        val item = VoiceCommandParser.parse("۱۰ تا رب ۷۰۰ چین چین").items.single()
        assertEquals("رب 700 چین چین", item.shownName)
    }

    // ---- reported 2: «یه روغن یه ربع یه پنیر صبا» with general names → three separate lines

    private val shop = listOf(
        p(10, "روغن طبیعت ۸۱۰ گرمی"), p(11, "روغن لادن ۱.۵ لیتری"),
        p(12, "رب گوجه فرنگی چین چین ۷۰۰ گرمی"), p(13, "رب گوجه فرنگی تبرک ۸۰۰ گرمی"),
        p(14, "پنیر صبا"), p(15, "پسته اکبری"),
    )
    private fun shopLines(s: String) = VoiceResolver.resolve(VoiceCommandParser.parse(s), shop, forPurchase = false)

    @Test fun generalNamesEachGetTheirOwnLineAndQuestion() {
        val l = shopLines("یه روغن یه ربع یه پنیر صبا")
        assertEquals(3, l.size)
        assertEquals(setOf(10L, 11L), l[0].options.map { it.id }.toSet())   // which oil?
        assertEquals(setOf(12L, 13L), l[1].options.map { it.id }.toSet())   // «ربع» heard for «رب»: which paste?
        assertEquals(14L, l[2].product?.id)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), l.map { it.quantityMilli })
        l.forEach { assertTrue(!it.heard.shownName.contains("#")) }
    }

    @Test fun quarterKiloIsAQuantity() {
        val l = VoiceCommandParser.parse("یه ربع کیلو پسته").items.single()
        assertEquals("پسته", l.name); assertEquals(250L, l.quantityMilli)
        assertEquals(750L, VoiceCommandParser.parse("سه ربع کیلو پسته").items.single().quantityMilli)
        val two = VoiceCommandParser.parse("دو کیلو پسته یه ربع کیلو پنیر").items
        assertEquals(listOf(2_000L, 250L), two.map { it.quantityMilli })
    }
}
