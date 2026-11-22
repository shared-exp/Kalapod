package ir.kalapod.app.data.repository

import ir.kalapod.app.data.local.dao.ProductDao
import ir.kalapod.app.data.local.dao.ReportDao
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.domain.forecast.ForecastCalculator
import ir.kalapod.app.domain.forecast.ForecastInput
import ir.kalapod.app.domain.forecast.ProductForecast
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the demand forecast for every active product over the last [LOOKBACK_DAYS] of real sales. */
@Singleton
class ForecastRepository @Inject constructor(
    private val products: ProductDao,
    private val reports: ReportDao,
) {
    suspend fun all(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<ProductForecast> {
        val windowStart = today.minusDays(LOOKBACK_DAYS - 1L)
        val from = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val byProduct = reports.saleLines(from, to, null).groupBy { it.productId }
        return products.observeActive().first().map { p ->
            val daily = byProduct[p.id].orEmpty().groupBy {
                Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate()
            }.mapValues { (_, ls) -> ls.sumOf { l -> if (l.type == TransactionType.SALE_RETURN) -l.quantityMilli else l.quantityMilli } }
            val created = Instant.ofEpochMilli(p.createdAt).atZone(zone).toLocalDate()
            // A product created recently has only that much history, not a run of fake zero days.
            val firstSale = daily.keys.minOrNull()
            val start = listOfNotNull(created, firstSale).minOrNull()!!.coerceAtLeast(windowStart)
            ForecastCalculator.forecast(
                ForecastInput(p.id, p.name, p.unit, p.stockMilli, p.minStockMilli, p.leadTimeDays, start, daily), today,
            )
        }.sortedWith(
            compareByDescending<ProductForecast> { it.shouldOrder }
                .thenBy { it.daysToStockout ?: Double.MAX_VALUE }
                .thenBy { it.name },
        )
    }

    companion object { const val LOOKBACK_DAYS = 56 }
}
