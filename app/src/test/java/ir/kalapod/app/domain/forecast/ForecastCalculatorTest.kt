package ir.kalapod.app.domain.forecast

import ir.kalapod.app.data.local.entity.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ForecastCalculatorTest {
    private val today = LocalDate.of(2026, 11, 21)

    private fun input(days: Int, stock: Long, lead: Int = 2, unit: ProductUnit = ProductUnit.PIECE, perDay: (LocalDate) -> Long) =
        ForecastInput(
            1, "روغن", unit, stock, 0, lead, today.minusDays(days - 1L),
            (0 until days).associate { today.minusDays(it.toLong()).let { d -> d to perDay(d) } },
        )

    @Test fun refusesWithTooLittleData() {
        val f = ForecastCalculator.forecast(input(4, 10_000) { 2_000 }, today)
        assertEquals(Confidence.INSUFFICIENT, f.confidence)
        assertTrue(f.note!!.contains("داده کافی نیست"))
        assertNull(f.daysToStockout)
    }

    @Test fun steadyDemandReorder() {
        // 30 days × exactly 2 pcs/day, 6 in stock, lead 2 days → sd = 0, reorder point = 4
        val f = ForecastCalculator.forecast(input(30, 6_000) { 2_000 }, today)
        assertEquals(Confidence.NORMAL, f.confidence)
        assertEquals(2_000L, f.avgDailyMilli)
        assertEquals(14_000L, f.avgWeeklyMilli)
        assertEquals(3.0, f.daysToStockout!!, 0.001)
        assertEquals(4_000L, f.reorderPointMilli)
        assertEquals(0L, f.suggestedOrderMilli) // 6 > 4: not yet

        val low = ForecastCalculator.forecast(input(30, 3_000) { 2_000 }, today)
        // target = 2 × (2 + 7) = 18 → order 15
        assertEquals(15_000L, low.suggestedOrderMilli)
        assertEquals(Trend.STEADY, low.trend)
    }

    @Test fun variableDemandAddsSafetyStock() {
        val f = ForecastCalculator.forecast(input(30, 0) { if (it.dayOfMonth % 2 == 0) 4_000 else 0 }, today)
        assertTrue(f.safetyStockMilli > 0)
        assertTrue(f.reorderPointMilli > f.avgDailyMilli * 2)
    }

    @Test fun trendAndWeekday() {
        val up = ForecastCalculator.forecast(input(28, 100_000) { if (it.isAfter(today.minusDays(14))) 5_000 else 2_000 }, today)
        assertEquals(Trend.UP, up.trend)
        val fridays = ForecastCalculator.forecast(input(35, 100_000) { if (it.dayOfWeek == DayOfWeek.FRIDAY) 10_000 else 1_000 }, today)
        assertEquals(DayOfWeek.FRIDAY, fridays.busiestDay)
    }

    @Test fun lowConfidenceBetweenOneAndFourWeeks() {
        val f = ForecastCalculator.forecast(input(10, 50_000) { 1_000 }, today)
        assertEquals(Confidence.LOW, f.confidence)
        assertEquals(Trend.UNKNOWN, f.trend)
    }

    @Test fun rounding() {
        assertEquals(2_500L, ForecastCalculator.roundUp(2_100, ProductUnit.KILOGRAM))
        assertEquals(3_000L, ForecastCalculator.roundUp(2_100, ProductUnit.PIECE))
        assertEquals(0L, ForecastCalculator.roundUp(-5, ProductUnit.PIECE))
    }
}
