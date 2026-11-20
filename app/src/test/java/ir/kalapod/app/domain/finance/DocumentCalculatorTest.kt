package ir.kalapod.app.domain.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentCalculatorTest {
    private val rice = LineInput(1, 10_000, 1_800_000)   // 10 kg × 180,000 T
    private val oil = LineInput(2, 3_000, 900_000)       // 3 × 90,000 T

    @Test fun totalsWithDiscountAndExtra() {
        val t = DocumentCalculator.compute(listOf(rice, oil), discount = 100_000, extraCost = 50_000)
        assertEquals(listOf(18_000_000L, 2_700_000L), t.lineTotals)
        assertEquals(20_700_000L, t.subtotal)
        assertEquals(20_650_000L, t.total)
        assertEquals(t.total, t.netLineValues.sum())
    }

    @Test fun validation() {
        assertNull(DocumentCalculator.validate(listOf(rice), 0, 0))
        assertEquals(DocumentCalculator.Problem.Empty, DocumentCalculator.validate(emptyList(), 0, 0))
        assertEquals(DocumentCalculator.Problem.BadQuantity, DocumentCalculator.validate(listOf(rice.copy(quantityMilli = 0)), 0, 0))
        assertEquals(DocumentCalculator.Problem.DiscountTooLarge, DocumentCalculator.validate(listOf(oil), 3_000_000, 0))
    }
}
