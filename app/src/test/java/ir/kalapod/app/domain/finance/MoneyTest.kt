package ir.kalapod.app.domain.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun tomanRialConversion() {
        assertEquals(1_800_000L, Money.tomanToRial(180_000))
        assertEquals(180_000L, Money.rialToToman(1_800_000))
        assertEquals(2L, Money.rialToToman(15)) // half-up
    }

    @Test fun lineTotalWithFractionalQuantity() {
        // 2.5 kg at 1,800,000 rial/kg
        assertEquals(4_500_000L, Money.lineTotal(1_800_000, 2_500))
        // 333 g at 10 rial/kg = 3.33 → 3
        assertEquals(3L, Money.lineTotal(10, 333))
        // 350 g at 10 rial/kg = 3.5 → 4
        assertEquals(4L, Money.lineTotal(10, 350))
    }

    @Test fun roundingNegative() {
        assertEquals(-4L, Money.divRoundHalfUp(-35, 10))
        assertEquals(-3L, Money.divRoundHalfUp(-34, 10))
    }

    @Test fun allocateSumsExactly() {
        val parts = Money.allocate(100, listOf(1, 1, 1))
        assertEquals(100L, parts.sum())
        assertEquals(listOf(34L, 33L, 33L), parts)
        assertEquals(listOf(-50L, -50L), Money.allocate(-100, listOf(5, 5)))
        assertEquals(listOf(0L, 7L), Money.allocate(7, listOf(0, 3)))
        assertEquals(10L, Money.allocate(10, listOf(0, 0, 0)).sum())
    }
}
