package ir.kalapod.app.domain.finance

/** One line of a purchase/sale document. [unitPrice] is per whole unit, in rial. */
/** [salePrice]: purchases only — the sale price of the batch this line brings in (null = keep the current one). */
data class LineInput(val productId: Long, val quantityMilli: Long, val unitPrice: Long, val salePrice: Long? = null)

data class DocumentTotals(
    val lineTotals: List<Long>,
    val subtotal: Long,
    val discount: Long,
    val extraCost: Long,
    val total: Long,
    /**
     * Net value of each line after spreading the invoice discount and extra cost by line weight.
     * For purchases this is the inventory cost of the line; for sales it is the net revenue.
     */
    val netLineValues: List<Long>,
)

object DocumentCalculator {
    sealed class Problem(val message: String) {
        data object Empty : Problem("هیچ کالایی در فاکتور نیست.")
        data object BadQuantity : Problem("مقدار هر قلم باید بیشتر از صفر باشد.")
        data object BadPrice : Problem("قیمت نمی‌تواند منفی باشد.")
        data object DiscountTooLarge : Problem("تخفیف از جمع فاکتور بیشتر است.")
        data object NegativeAmount : Problem("تخفیف و هزینه نمی‌توانند منفی باشند.")
    }

    fun validate(lines: List<LineInput>, discount: Long, extraCost: Long): Problem? = when {
        lines.isEmpty() -> Problem.Empty
        lines.any { it.quantityMilli <= 0 } -> Problem.BadQuantity
        lines.any { it.unitPrice < 0 } -> Problem.BadPrice
        discount < 0 || extraCost < 0 -> Problem.NegativeAmount
        discount > lines.sumOf { Money.lineTotal(it.unitPrice, it.quantityMilli) } -> Problem.DiscountTooLarge
        else -> null
    }

    fun compute(lines: List<LineInput>, discount: Long, extraCost: Long): DocumentTotals {
        val lineTotals = lines.map { Money.lineTotal(it.unitPrice, it.quantityMilli) }
        val subtotal = lineTotals.sum()
        val discountParts = Money.allocate(discount, lineTotals)
        val extraParts = Money.allocate(extraCost, lineTotals)
        val net = lineTotals.indices.map { lineTotals[it] - discountParts[it] + extraParts[it] }
        return DocumentTotals(lineTotals, subtotal, discount, extraCost, subtotal - discount + extraCost, net)
    }
}
