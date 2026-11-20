package ir.kalapod.app.domain.finance

/**
 * Stock of one product under the moving weighted average cost method.
 *
 * [quantityMilli] and [value] are exactly the sums of the inventory ledger, so a position can always
 * be rebuilt by replaying movements. Every operation returns the ledger delta it implies.
 */
data class StockPosition(val quantityMilli: Long = 0, val value: Long = 0) {

    data class Movement(val quantityDeltaMilli: Long, val valueDelta: Long)

    /** Average cost per whole unit, or null when there is no positive stock. */
    val averageUnitCost: Long?
        get() = if (quantityMilli > 0) Money.divRoundHalfUp(value * Money.MILLI, quantityMilli) else null

    /** Goods received (purchase or a sale return) at a known total cost. */
    fun receive(quantityMilli: Long, totalCost: Long): Pair<StockPosition, Movement> {
        require(quantityMilli > 0) { "quantity must be positive" }
        require(totalCost >= 0) { "cost must be non-negative" }
        val m = Movement(quantityMilli, totalCost)
        return apply(m) to m
    }

    /**
     * Goods leaving stock (sale or purchase return). Cost is taken at the current moving average.
     * If stock is insufficient (user knowingly oversold), the uncovered part is costed at
     * [fallbackUnitCost] (typically the last purchase price) so profit is still estimated sensibly.
     * Issuing all remaining stock removes exactly the remaining value (no rounding residue).
     */
    fun issue(quantityMilli: Long, fallbackUnitCost: Long): Pair<StockPosition, Movement> {
        require(quantityMilli > 0) { "quantity must be positive" }
        val covered = quantityMilli.coerceAtMost(this.quantityMilli.coerceAtLeast(0))
        val uncovered = quantityMilli - covered
        val coveredCost = when {
            covered == 0L -> 0L
            covered == this.quantityMilli -> value
            else -> Money.divRoundHalfUp(Math.multiplyExact(value, covered), this.quantityMilli)
        }
        val uncoveredCost = Money.lineTotal(fallbackUnitCost, uncovered)
        val m = Movement(-quantityMilli, -(coveredCost + uncoveredCost))
        return apply(m) to m
    }

    /** Manual correction (count difference). Positive adds at average/fallback cost, negative issues. */
    fun adjust(deltaMilli: Long, fallbackUnitCost: Long): Pair<StockPosition, Movement> {
        require(deltaMilli != 0L) { "delta must be non-zero" }
        return if (deltaMilli > 0) {
            val unitCost = averageUnitCost ?: fallbackUnitCost
            receive(deltaMilli, Money.lineTotal(unitCost, deltaMilli))
        } else {
            issue(-deltaMilli, fallbackUnitCost)
        }
    }

    fun apply(m: Movement) = StockPosition(quantityMilli + m.quantityDeltaMilli, value + m.valueDelta)

    companion object {
        fun replay(movements: Iterable<Movement>): StockPosition =
            movements.fold(StockPosition()) { acc, m -> acc.apply(m) }
    }
}
