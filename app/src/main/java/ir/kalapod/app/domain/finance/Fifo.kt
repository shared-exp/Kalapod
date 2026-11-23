package ir.kalapod.app.domain.finance

/**
 * First-in-first-out stock batches (بار). Every purchase brings in a batch with its own cost and its own
 * sale price; goods always leave the oldest batch that still has stock, so an old batch is sold out at its
 * own price before the next one starts (no averaging between batches).
 */
object Fifo {

    /** What is left of one batch. [salePrice] is null for batches with no price of their own. */
    data class Batch(val id: Long, val remainingMilli: Long, val remainingValue: Long, val salePrice: Long?)

    /** A quantity taken from one batch, at that batch's cost. [batchId] null = there is no batch at all. */
    data class Take(val batchId: Long?, val quantityMilli: Long, val cost: Long)

    /** A run of the same sale price: [quantityMilli] units at [price]. */
    data class PriceTier(val quantityMilli: Long, val price: Long)

    /**
     * Takes [quantityMilli] oldest-first. A part not covered by stock (the user knowingly oversold) is
     * charged to the newest batch at [fallbackUnitCost]; the next purchase settles that deficit.
     */
    fun take(batches: List<Batch>, quantityMilli: Long, fallbackUnitCost: Long): List<Take> {
        require(quantityMilli > 0) { "quantity must be positive" }
        val out = mutableListOf<Take>()
        var left = quantityMilli
        for (b in batches) {
            if (left == 0L) break
            if (b.remainingMilli <= 0) continue
            val q = minOf(left, b.remainingMilli)
            val cost = -StockPosition(b.remainingMilli, b.remainingValue).issue(q, fallbackUnitCost).second.valueDelta
            out += Take(b.id, q, cost)
            left -= q
        }
        if (left > 0) out += Take(batches.lastOrNull()?.id, left, Money.lineTotal(fallbackUnitCost, left))
        return out
    }

    /** The batch now on sale: the oldest one with stock. */
    fun front(batches: List<Batch>): Batch? = batches.firstOrNull { it.remainingMilli > 0 }

    /**
     * How [quantityMilli] would be priced if sold now: each batch at its own price, oldest first; anything
     * beyond the stock at the newest known price (or [fallbackPrice]). Neighbouring equal prices are merged.
     */
    fun priceTiers(batches: List<Batch>, quantityMilli: Long, fallbackPrice: Long): List<PriceTier> {
        val out = mutableListOf<PriceTier>()
        fun add(q: Long, price: Long) {
            if (q <= 0) return
            val last = out.lastOrNull()
            if (last != null && last.price == price) out[out.lastIndex] = last.copy(quantityMilli = last.quantityMilli + q)
            else out += PriceTier(q, price)
        }
        var left = quantityMilli
        var lastPrice = fallbackPrice
        for (b in batches) {
            if (left == 0L) break
            if (b.remainingMilli <= 0) continue
            val price = b.salePrice ?: lastPrice
            val q = minOf(left, b.remainingMilli)
            add(q, price)
            lastPrice = price
            left -= q
        }
        add(left, lastPrice)
        return out
    }
}
