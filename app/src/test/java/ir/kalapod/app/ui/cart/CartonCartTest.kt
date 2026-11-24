package ir.kalapod.app.ui.cart

import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class CartonCartTest {
    private val line = CartLine(1, "کیک نادری", ProductUnit.PIECE, "1", "4000", 0, knownCaseSizeMilli = 12_000)

    @Test fun cartonLineSendsPiecesAndExactTotal() {
        val c = line.inCases(10_000, 12_000, 480_000, Currency.RIAL)
        val input = c.toInput(Currency.RIAL)!!
        assertEquals(120_000L, input.quantityMilli)
        assertEquals(40_000L, input.unitPrice)
        assertEquals(4_800_000L, input.lineTotal)
        assertEquals(4_800_000L, c.lineTotal(Currency.RIAL))
        assertEquals(120_000L, c.pieces())
    }

    @Test fun toggleKeepsGoodsAndMoney() {
        val pieces = line.copy(quantityText = "24", priceText = "40000")
        val cases = pieces.toggleCases(Currency.RIAL)
        assertEquals(2_000L, cases.quantityMilli())
        assertEquals(480_000L, cases.unitPrice(Currency.RIAL))
        val back = cases.toggleCases(Currency.RIAL)
        assertEquals(24_000L, back.quantityMilli())
        assertEquals(40_000L, back.unitPrice(Currency.RIAL))
    }

    @Test fun stockInCartons() {
        assertEquals("۱۰ کارتن (۱۲۰ عدد)", PersianFormat.withCases(120_000, "عدد", 12_000))
        assertEquals("۹ کارتن و ۶ عدد (۱۱۴ عدد)", PersianFormat.withCases(114_000, "عدد", 12_000))
        assertEquals("۶ عدد", PersianFormat.withCases(6_000, "عدد", 12_000))
        assertEquals("۶ عدد", PersianFormat.withCases(6_000, "عدد", null))
    }
}
