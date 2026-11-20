package ir.kalapod.app.domain.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockPositionTest {

    @Test fun movingWeightedAverage() {
        var p = StockPosition()
        // 10 kg at 100 rial/kg, then 10 kg at 200 rial/kg → avg 150
        p = p.receive(10_000, 1_000).first
        p = p.receive(10_000, 2_000).first
        assertEquals(150L, p.averageUnitCost)

        // Sell 4 kg → cost 600, profit vs sale price 250/kg = 400
        val (after, m) = p.issue(4_000, fallbackUnitCost = 200)
        assertEquals(-600L, m.valueDelta)
        assertEquals(16_000L, after.quantityMilli)
        assertEquals(2_400L, after.value)
        assertEquals(150L, after.averageUnitCost)
    }

    @Test fun issuingEverythingLeavesZeroValue() {
        var p = StockPosition().receive(3_000, 100).first // avg 33.33
        p = p.issue(1_000, 0).first
        p = p.issue(1_000, 0).first
        p = p.issue(1_000, 0).first
        assertEquals(0L, p.quantityMilli)
        assertEquals(0L, p.value)
        assertNull(p.averageUnitCost)
    }

    @Test fun oversellUsesFallbackForUncoveredPart() {
        val p = StockPosition().receive(1_000, 100).first
        val (after, m) = p.issue(3_000, fallbackUnitCost = 120)
        assertEquals(-(100L + 240L), m.valueDelta)
        assertEquals(-2_000L, after.quantityMilli)
        // Restock after negative stock: value catches up.
        val restocked = after.receive(5_000, 600).first
        assertEquals(3_000L, restocked.quantityMilli)
    }

    @Test fun adjustmentUsesAverageCost() {
        val p = StockPosition().receive(10_000, 1_500).first
        val (_, up) = p.adjust(2_000, 999)
        assertEquals(300L, up.valueDelta)
        val (_, down) = p.adjust(-2_000, 999)
        assertEquals(-300L, down.valueDelta)
    }

    @Test fun replayRebuildsPosition() {
        val moves = mutableListOf<StockPosition.Movement>()
        var p = StockPosition()
        p.receive(5_000, 900).also { p = it.first; moves += it.second }
        p.issue(2_000, 0).also { p = it.first; moves += it.second }
        p.receive(1_500, 400).also { p = it.first; moves += it.second }
        assertEquals(p, StockPosition.replay(moves))
    }
}
