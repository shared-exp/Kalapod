package ir.kalapod.app.ui.cart

import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.domain.finance.LineInput
import ir.kalapod.app.domain.finance.Money

/**
 * Editable cart line shared by purchase and sale screens (and later voice/barcode entry).
 * Fields are kept as typed text so the user can edit freely; parsing happens on demand.
 */
data class CartLine(
    val productId: Long,
    val name: String,
    val unit: ProductUnit,
    val quantityText: String,
    val priceText: String,
    /** Current stock, for oversell warnings on sales. */
    val stockMilli: Long,
) {
    fun quantityMilli(): Long? = InputParser.quantity(quantityText)?.takeIf { it > 0 }
    fun unitPrice(currency: Currency): Long? = InputParser.money(priceText, currency)
    fun lineTotal(currency: Currency): Long? {
        val q = quantityMilli() ?: return null
        val p = unitPrice(currency) ?: return null
        return Money.lineTotal(p, q)
    }
    fun toInput(currency: Currency): LineInput? {
        val q = quantityMilli() ?: return null
        val p = unitPrice(currency) ?: return null
        return LineInput(productId, q, p)
    }

    companion object {
        fun of(p: ProductEntity, priceRial: Long, currency: Currency, quantityMilli: Long = Money.MILLI) = CartLine(
            productId = p.id, name = p.name, unit = p.unit,
            quantityText = InputParser.quantityForEdit(quantityMilli),
            priceText = if (priceRial > 0) InputParser.moneyForEdit(priceRial, currency) else "",
            stockMilli = p.stockMilli,
        )
    }
}

/** How a document is settled. CREDIT = some or all of it stays as a debt (نسیه). */
enum class Settlement(val label: String) { CASH("نقد"), CARD("کارت"), CREDIT("نسیه") }

/** Adds a product, or bumps its quantity by one unit if it's already in the cart. */
fun List<CartLine>.addOrIncrement(line: CartLine): List<CartLine> {
    val i = indexOfFirst { it.productId == line.productId }
    if (i < 0) return this + line
    val existing = this[i]
    val q = (existing.quantityMilli() ?: 0L) + (line.quantityMilli() ?: Money.MILLI)
    return toMutableList().also { it[i] = existing.copy(quantityText = InputParser.quantityForEdit(q)) }
}
