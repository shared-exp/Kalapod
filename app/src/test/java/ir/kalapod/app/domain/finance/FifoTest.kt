package ir.kalapod.app.domain.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class FifoTest {
    private val batches = listOf(
        Fifo.Batch(1, 0, 0, 9_000),            // sold out
        Fifo.Batch(2, 20_000, 160_000, 10_000),
        Fifo.Batch(3, 5_000, 45_000, 11_000),
    )

    @Test fun takesOldestFirstAtEachBatchCost() {
        assertEquals(
            listOf(Fifo.Take(2, 20_000, 160_000), Fifo.Take(3, 2_000, 18_000)),
            Fifo.take(batches, 22_000, fallbackUnitCost = 1),
        )
    }

    @Test fun oversellChargesTheNewestBatchAtFallbackCost() {
        assertEquals(Fifo.Take(3, 1_000, 9_500), Fifo.take(batches, 26_000, fallbackUnitCost = 9_500).last())
    }

    @Test fun priceTiersFollowBatches() {
        assertEquals(listOf(Fifo.PriceTier(3_000, 10_000)), Fifo.priceTiers(batches, 3_000, 1))
        assertEquals(
            listOf(Fifo.PriceTier(20_000, 10_000), Fifo.PriceTier(7_000, 11_000)),
            Fifo.priceTiers(batches, 27_000, 1),
        )
        assertEquals(listOf(Fifo.PriceTier(2_000, 500)), Fifo.priceTiers(emptyList(), 2_000, 500))
    }
}
