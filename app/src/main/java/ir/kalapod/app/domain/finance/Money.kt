package ir.kalapod.app.domain.finance

/**
 * Pure integer arithmetic for money and quantities. No floating point anywhere.
 *
 * Money is Long RIAL. Quantity is Long milli-units (1000 = one kg / one piece / ...).
 */
object Money {
    const val MILLI = 1000L
    const val RIAL_PER_TOMAN = 10L

    fun tomanToRial(toman: Long): Long = Math.multiplyExact(toman, RIAL_PER_TOMAN)

    /** Rial → Toman, rounded half-up (display only). */
    fun rialToToman(rial: Long): Long = divRoundHalfUp(rial, RIAL_PER_TOMAN)

    /** Price of [quantityMilli] at [unitPrice] per whole unit, rounded half-up to the rial. */
    fun lineTotal(unitPrice: Long, quantityMilli: Long): Long =
        divRoundHalfUp(Math.multiplyExact(unitPrice, quantityMilli), MILLI)

    /** Integer division rounding half away from zero. */
    fun divRoundHalfUp(numerator: Long, denominator: Long): Long {
        require(denominator != 0L) { "division by zero" }
        val q = numerator / denominator
        val r = numerator % denominator
        return if (Math.abs(r) * 2 >= Math.abs(denominator)) {
            q + if ((numerator < 0) xor (denominator < 0)) -1 else 1
        } else {
            q
        }
    }

    /**
     * Splits [amount] across [weights] proportionally so the parts sum exactly to [amount]
     * (largest-remainder method). Used to spread invoice discount/extra cost over lines.
     */
    fun allocate(amount: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        require(weights.all { it >= 0 }) { "weights must be non-negative" }
        val totalWeight = weights.sum()
        if (totalWeight == 0L) {
            // Spread evenly.
            return allocate(amount, List(weights.size) { 1L })
        }
        val sign = if (amount < 0) -1 else 1
        val abs = Math.abs(amount)
        val base = LongArray(weights.size)
        val remainders = LongArray(weights.size)
        weights.forEachIndexed { i, w ->
            val product = Math.multiplyExact(abs, w)
            base[i] = product / totalWeight
            remainders[i] = product % totalWeight
        }
        var leftover = abs - base.sum()
        remainders.withIndex().sortedByDescending { it.value }.forEach { (i, _) ->
            if (leftover > 0) { base[i]++; leftover-- }
        }
        return base.map { it * sign }
    }
}
