package ir.kalapod.app.data.repository

import ir.kalapod.app.data.local.dao.ProductDao
import ir.kalapod.app.data.local.dao.TransactionDao
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.TransactionType.PURCHASE
import ir.kalapod.app.data.local.entity.TransactionType.PURCHASE_RETURN
import ir.kalapod.app.data.local.entity.TransactionType.SALE
import ir.kalapod.app.data.local.entity.TransactionType.SALE_RETURN
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class DaySummary(
    /** Net sales: sales minus sale returns (rial). */
    val netSales: Long,
    val saleCount: Int,
    /** Net purchases: purchases minus purchase returns (rial). */
    val netPurchases: Long,
    /** Gross profit estimate: sale margins − sale discounts − margin given back by returns. */
    val grossProfit: Long,
)

data class DashboardData(
    val today: DaySummary,
    val stockValue: Long,
    val lowStock: List<ProductEntity>,
)

@Singleton
class DashboardRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val productDao: ProductDao,
) {
    fun daySummary(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<DaySummary> {
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val sales = combine(
            transactionDao.observeTotal(SALE, from, to),
            transactionDao.observeTotal(SALE_RETURN, from, to),
            transactionDao.observeCount(SALE, from, to),
        ) { s, r, c -> (s - r) to c }
        val purchases = combine(
            transactionDao.observeTotal(PURCHASE, from, to),
            transactionDao.observeTotal(PURCHASE_RETURN, from, to),
        ) { p, r -> p - r }
        val profit = combine(
            transactionDao.observeLineMargin(SALE, from, to),
            transactionDao.observeDiscounts(SALE, from, to),
            transactionDao.observeLineMargin(SALE_RETURN, from, to),
            transactionDao.observeDiscounts(SALE_RETURN, from, to),
        ) { margin, discount, retMargin, retDiscount -> (margin - discount) - (retMargin - retDiscount) }
        return combine(sales, purchases, profit) { s, p, g ->
            DaySummary(netSales = s.first, saleCount = s.second, netPurchases = p, grossProfit = g)
        }
    }

    fun dashboard(day: LocalDate): Flow<DashboardData> = combine(
        daySummary(day),
        productDao.observeStockValue(),
        productDao.observeLowStock(),
    ) { summary, value, low -> DashboardData(summary, value, low) }
}
