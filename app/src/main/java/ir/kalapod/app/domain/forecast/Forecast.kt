package ir.kalapod.app.domain.forecast

import ir.kalapod.app.data.local.entity.ProductUnit
import java.time.DayOfWeek
import java.time.LocalDate
import ir.kalapod.app.core.format.PersianFormat
import kotlin.math.ceil
import kotlin.math.sqrt

enum class Confidence { INSUFFICIENT, LOW, NORMAL }
enum class Trend { UP, DOWN, STEADY, UNKNOWN }

data class ForecastInput(
    val productId: Long,
    val name: String,
    val unit: ProductUnit,
    val stockMilli: Long,
    val minStockMilli: Long,
    val leadTimeDays: Int,
    /** First day the product could have sold (created, or start of the look-back window). */
    val historyStart: LocalDate,
    /** Net quantity sold per day (sales − returns), milli-units; missing days = 0. */
    val dailySold: Map<LocalDate, Long>,
)

data class ProductForecast(
    val productId: Long,
    val name: String,
    val unit: ProductUnit,
    val confidence: Confidence,
    /** Honest Persian explanation when confidence is not NORMAL. */
    val note: String?,
    val historyDays: Int,
    val avgDailyMilli: Long,
    val avgWeeklyMilli: Long,
    val trend: Trend,
    /** Busiest weekday, if there's enough data to tell. */
    val busiestDay: DayOfWeek?,
    /** Null when nothing is selling (no stockout expected). */
    val daysToStockout: Double?,
    val reorderPointMilli: Long,
    val safetyStockMilli: Long,
    /** 0 = no need to order now. */
    val suggestedOrderMilli: Long,
    val stockMilli: Long,
) {
    val shouldOrder get() = suggestedOrderMilli > 0
}

/**
 * Lightweight, explainable demand statistics (SPEC §9): moving averages, a simple trend test,
 * weekday pattern, and a classic reorder-point model with safety stock. No machine learning, and it
 * refuses to forecast when there isn't enough history.
 */
object ForecastCalculator {
    /** Days of cover we aim for beyond the supplier lead time (roughly "until next week's order"). */
    const val REVIEW_DAYS = 7
    private const val Z_95 = 1.65
    private const val MIN_DAYS = 7
    private const val MIN_SALE_DAYS = 3
    private const val NORMAL_DAYS = 28

    fun forecast(input: ForecastInput, today: LocalDate): ProductForecast {
        val start = input.historyStart.coerceAtMost(today)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val series = days.map { input.dailySold[it] ?: 0L }
        val historyDays = days.size
        val saleDays = series.count { it > 0 }

        if (historyDays < MIN_DAYS || saleDays < MIN_SALE_DAYS) {
            return ProductForecast(
                input.productId, input.name, input.unit, Confidence.INSUFFICIENT,
                "داده کافی نیست: ${n(historyDays)} روز سابقه و ${n(saleDays)} روز فروش. برای پیش‌بینی دست‌کم ${n(MIN_DAYS)} روز سابقه و ${n(MIN_SALE_DAYS)} روز فروش لازم است.",
                historyDays, 0, 0, Trend.UNKNOWN, null, null, input.minStockMilli, 0,
                // Without a forecast, fall back to the user's own minimum stock rule.
                if (input.minStockMilli > 0 && input.stockMilli <= input.minStockMilli) roundUp(input.minStockMilli * 2 - input.stockMilli, input.unit) else 0,
                input.stockMilli,
            )
        }

        // Use the most recent 28 days (or all we have) for the level; more history is for trend/pattern.
        val recent = series.takeLast(NORMAL_DAYS)
        val avg = recent.average()                       // milli per day
        val sd = sqrt(recent.map { (it - avg) * (it - avg) }.average())
        val lead = input.leadTimeDays.coerceAtLeast(1)

        val trend = if (series.size >= 28) {
            val last = series.takeLast(14).average()
            val prev = series.dropLast(14).takeLast(14).average()
            when {
                prev == 0.0 && last > 0 -> Trend.UP
                prev == 0.0 -> Trend.STEADY
                last / prev >= 1.2 -> Trend.UP
                last / prev <= 0.8 -> Trend.DOWN
                else -> Trend.STEADY
            }
        } else Trend.UNKNOWN

        val busiest = if (historyDays >= 28) {
            days.zip(series).groupBy({ it.first.dayOfWeek }, { it.second }).mapValues { it.value.average() }
                .maxByOrNull { it.value }?.takeIf { top -> top.value > avg * 1.25 }?.key
        } else null

        val safety = (Z_95 * sd * sqrt(lead.toDouble())).toLong()
        val reorderPoint = (avg * lead).toLong() + safety
        val target = (avg * (lead + REVIEW_DAYS)).toLong() + safety
        val suggested = if (input.stockMilli <= maxOf(reorderPoint, input.minStockMilli)) {
            roundUp(target - input.stockMilli, input.unit)
        } else 0

        val confidence = if (historyDays >= NORMAL_DAYS) Confidence.NORMAL else Confidence.LOW
        return ProductForecast(
            input.productId, input.name, input.unit, confidence,
            if (confidence == Confidence.LOW) "تخمین اولیه بر پایه ${n(historyDays)} روز سابقه؛ با گذشت زمان دقیق‌تر می‌شود." else null,
            historyDays, avg.toLong(), (avg * 7).toLong(), trend, busiest,
            if (avg > 0) input.stockMilli.coerceAtLeast(0) / avg else null,
            reorderPoint, safety, suggested.coerceAtLeast(0), input.stockMilli,
        )
    }

    private fun n(v: Int) = PersianFormat.number(v.toLong())

    /** Whole units for countable goods; half units for weight/volume. */
    internal fun roundUp(milli: Long, unit: ProductUnit): Long {
        if (milli <= 0) return 0
        val step = when (unit) {
            ProductUnit.KILOGRAM, ProductUnit.LITER, ProductUnit.METER -> 500L
            ProductUnit.GRAM -> 1_000L * 50 // 50 g
            else -> 1_000L
        }
        return ceil(milli.toDouble() / step).toLong() * step
    }
}
