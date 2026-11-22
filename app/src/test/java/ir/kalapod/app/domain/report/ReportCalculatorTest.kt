package ir.kalapod.app.domain.report

import ir.kalapod.app.data.local.dao.DocTotalRow
import ir.kalapod.app.data.local.dao.ProductName
import ir.kalapod.app.data.local.dao.SaleLineRow
import ir.kalapod.app.data.local.entity.TransactionType.PURCHASE
import ir.kalapod.app.data.local.entity.TransactionType.SALE
import ir.kalapod.app.data.local.entity.TransactionType.SALE_RETURN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReportCalculatorTest {
    private val zone = ZoneOffset.UTC
    private val d1 = LocalDate.of(2026, 11, 18)
    private val d2 = LocalDate.of(2026, 11, 19)
    private fun at(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000

    private val lines = listOf(
        // Sale on d1: rice 2kg 4,000,000 (cost 3,600,000) + oil 1 pc 1,000,000 (cost 800,000), invoice discount 500,000
        SaleLineRow(SALE, at(d1), 1, "برنج", 2_000, 4_000_000, 3_600_000, 5_000_000, 500_000),
        SaleLineRow(SALE, at(d1), 2, "روغن", 1_000, 1_000_000, 800_000, 5_000_000, 500_000),
        // Sale on d2: oil 3 pcs, no discount
        SaleLineRow(SALE, at(d2) + 1, 2, "روغن", 3_000, 3_000_000, 2_400_000, 3_000_000, 0),
        // Return on d2 of 1 oil from the d2 sale
        SaleLineRow(SALE_RETURN, at(d2) + 2, 2, "روغن", 1_000, 1_000_000, 800_000, 1_000_000, 0),
    )
    private val docs = listOf(
        DocTotalRow(SALE, 4_500_000, at(d1)), DocTotalRow(SALE, 3_000_000, at(d2) + 1),
        DocTotalRow(SALE_RETURN, 1_000_000, at(d2) + 2), DocTotalRow(PURCHASE, 9_000_000, at(d1)),
    )
    private val products = listOf(ProductName(1, "برنج"), ProductName(2, "روغن"), ProductName(3, "ماکارونی"))

    @Test fun wholeShop() {
        val r = ReportCalculator.build(d1, d2, zone, lines, docs, products, null)
        assertEquals(6_500_000L, r.netSales)
        assertEquals(2, r.saleCount)
        assertEquals(9_000_000L, r.netPurchases)
        // (4.5M − 4.4M cost) + (3M − 2.4M) − (1M − 0.8M) = 100k + 600k − 200k
        assertEquals(500_000L, r.grossProfit)
        assertEquals(listOf(4_500_000L, 2_000_000L), r.daily.map { it.netSales })
    }

    @Test fun bestAndWorst() {
        val r = ReportCalculator.build(d1, d2, zone, lines, docs, products, null)
        // rice net = 4,000,000 − 400,000 share = 3,600,000; oil = 900,000 + 3,000,000 − 1,000,000 = 2,900,000
        assertEquals(listOf(1L, 2L), r.best.map { it.productId })
        assertEquals(3L, r.worst.first().productId)       // macaroni never sold
        assertEquals(0L, r.worst.first().quantityMilli)
    }

    @Test fun productFilter() {
        val oilOnly = lines.filter { it.productId == 2L }
        val r = ReportCalculator.build(d1, d2, zone, oilOnly, docs, products, 2)
        assertEquals(2_900_000L, r.netSales)
        assertEquals(3_000L, r.best.single().quantityMilli)
        assertTrue(r.worst.isEmpty())
    }

    @Test fun emptyPeriodIsZeroNotFake() {
        val r = ReportCalculator.build(d1, d2, zone, emptyList(), emptyList(), products, null)
        assertEquals(0L, r.netSales)
        assertEquals(0L, r.grossProfit)
        assertTrue(r.best.isEmpty())
        assertEquals(2, r.daily.size)
    }
}
