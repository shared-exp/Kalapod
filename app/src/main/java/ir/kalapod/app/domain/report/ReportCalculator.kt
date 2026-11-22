package ir.kalapod.app.domain.report

import ir.kalapod.app.data.local.dao.DocTotalRow
import ir.kalapod.app.data.local.dao.ProductName
import ir.kalapod.app.data.local.dao.SaleLineRow
import ir.kalapod.app.data.local.entity.TransactionType
import ir.kalapod.app.domain.finance.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ProductStat(val productId: Long, val name: String, val quantityMilli: Long, val netRevenue: Long, val profit: Long)

data class DayPoint(val date: LocalDate, val netSales: Long)

data class Report(
    val from: LocalDate,
    val to: LocalDate,
    /** Sales minus sale returns, after invoice discounts (rial). */
    val netSales: Long,
    val saleCount: Int,
    val netPurchases: Long,
    val grossProfit: Long,
    val daily: List<DayPoint>,
    val best: List<ProductStat>,
    /** Active products that sold least (including those that didn't sell at all). */
    val worst: List<ProductStat>,
)

/**
 * Pure report math over raw rows from the database. No fake data: an empty period yields zeros
 * and empty lists, and the UI says so.
 */
object ReportCalculator {

    fun build(
        from: LocalDate, to: LocalDate, zone: ZoneId,
        lines: List<SaleLineRow>, docs: List<DocTotalRow>, products: List<ProductName>,
        productFilter: Long?,
    ): Report {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        // Net revenue of a line = its total minus its proportional share of the invoice discount.
        fun net(l: SaleLineRow): Long {
            val share = if (l.txSubtotal > 0) Money.divRoundHalfUp(l.txDiscount * l.lineTotal, l.txSubtotal) else 0
            val v = l.lineTotal - share
            return if (l.type == TransactionType.SALE_RETURN) -v else v
        }
        fun cost(l: SaleLineRow) = if (l.type == TransactionType.SALE_RETURN) -l.costTotal else l.costTotal
        fun qty(l: SaleLineRow) = if (l.type == TransactionType.SALE_RETURN) -l.quantityMilli else l.quantityMilli

        val netSales: Long
        val saleCount: Int
        if (productFilter == null) {
            // Whole-shop totals come from document totals, which are exact (no per-line rounding).
            netSales = docs.sumOf {
                when (it.type) { TransactionType.SALE -> it.total; TransactionType.SALE_RETURN -> -it.total; else -> 0 }
            }
            saleCount = docs.count { it.type == TransactionType.SALE }
        } else {
            netSales = lines.sumOf(::net)
            saleCount = lines.filter { it.type == TransactionType.SALE }.map { it.occurredAt }.distinct().size
        }
        val grossProfit = lines.sumOf { net(it) - cost(it) }
        val netPurchases = if (productFilter == null) docs.sumOf {
            when (it.type) { TransactionType.PURCHASE -> it.total; TransactionType.PURCHASE_RETURN -> -it.total; else -> 0 }
        } else 0

        val byDay = lines.groupBy { day(it.occurredAt) }.mapValues { (_, ls) -> ls.sumOf(::net) }
        val daily = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { DayPoint(it, byDay[it] ?: 0) }.toList()

        val stats = lines.groupBy { it.productId }.map { (id, ls) ->
            ProductStat(id, ls.first().productName, ls.sumOf(::qty), ls.sumOf(::net), ls.sumOf { net(it) - cost(it) })
        }
        val best = stats.filter { it.netRevenue > 0 }.sortedByDescending { it.netRevenue }.take(10)
        val sold = stats.associateBy { it.productId }
        // Ranked by money (quantities in kg vs pieces aren't comparable), and never repeating the top sellers.
        val topIds = best.take(5).map { it.productId }.toSet()
        val worst = if (productFilter != null) emptyList() else products
            .filter { it.id !in topIds }
            .map { sold[it.id] ?: ProductStat(it.id, it.name, 0, 0, 0) }
            .sortedBy { it.netRevenue }
            .take(5)

        return Report(from, to, netSales, saleCount, netPurchases, grossProfit, daily, best, worst)
    }
}
